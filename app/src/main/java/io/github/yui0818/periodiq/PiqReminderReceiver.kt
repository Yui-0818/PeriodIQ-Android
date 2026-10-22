package io.github.yui0818.periodiq

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 提醒的唯一接收器（`exported="false"`：三条系统广播都是受保护广播，自己那条闹钟意图是显式组件意图）。
 *
 * 两种职责，都不起 Python、不碰 WebView、不开 Activity：
 *  1. **到点**（[PiqReminderScheduler.ACTION_REMIND]）⇒ 读落盘计划发通知。这一步刻意留在
 *     `onReceive` 里同步做完（只读一个几百字节的私有目录文件），因为接收器的进程随时可能被系统收走，
 *     甩给后台线程反而可能一条都发不出来；
 *  2. **重挂**（开机 / 时区变化 / 时间被改）⇒ 闹钟在这三种情况下会被系统清掉或失准，
 *     按落盘计划里的**本地触发日**用当前时区重新求 09:00 再挂回去（已过的那条自然不再挂）。
 *     排程本身不在这里做：那需要查库，而查库需要会话与预测，属于应用启动后的事。
 *
 * **重挂必须用 `goAsync()` 包住（R11.1 P3-3）**：`onReceive` 返回后系统随时可以回收本进程，
 * 只把活甩给执行器而不持有 `PendingResult` ⇒ 重挂可能被中途掐断，闹钟少挂且没有日志痕迹。
 * 因此三条系统广播一律：取 `goAsync()` → 在提醒层执行器线程重挂 → 完成（成功或失败）后
 * **恰好一次** `finish()`；`finish()` 成功返回后才写 `reminder-rearm|reason=<reason>|async=done`
 * （它失败时由 [PiqReminderScheduler.rearmFromStoreAsync] 记 `stage=finish`，不会留下假的完成行）。
 * 到点那条不走这条路（见上一条：它必须同步发完）。
 */
class PiqReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val action = intent.action
        Log.i(TAG, "reminder-receiver|action=$action|pid=${android.os.Process.myPid()}")
        when (action) {
            PiqReminderScheduler.ACTION_REMIND ->
                PiqReminderScheduler.fireNotification(app, intent.getIntExtra(PiqReminderScheduler.EXTRA_NOTIFY_ID, 0))

            Intent.ACTION_BOOT_COMPLETED -> rearmWithPendingResult(app, REASON_BOOT)
            Intent.ACTION_TIMEZONE_CHANGED -> rearmWithPendingResult(app, REASON_TIMEZONE)
            Intent.ACTION_TIME_CHANGED -> rearmWithPendingResult(app, REASON_TIME_SET)
        }
    }

    /**
     * 跨一次异步重挂的公共路径：持有 `PendingResult`，完成回调里先 `finish()`，成功返回后才写完成日志。
     *
     * [RearmFinishGate] 守住**恰好一次**；[startRearm] 兜住"提交阶段同步抛异常"
     * （执行器拒绝 / 上下文为空）——那时也必须完成一次，且日志写 `ok=false`。
     */
    private fun rearmWithPendingResult(context: Context, reason: String) {
        val pendingResult = goAsync()
        val gate = RearmFinishGate(
            finish = pendingResult::finish,
            log = { ok ->
                Log.i(TAG, "reminder-rearm|reason=$reason|async=done|ok=$ok|pid=${android.os.Process.myPid()}")
            },
            onFinishFailure = { error ->
                Log.w(
                    TAG,
                    "reminder-rearm|ok=false|reason=$reason|stage=finish" +
                        "|type=${error.javaClass.simpleName}|message=${error.message}",
                )
            },
        )
        startRearm(
            gate = gate,
            submit = { onDone -> PiqReminderScheduler.rearmFromStoreAsync(context, reason, onDone) },
            onRejected = { error ->
                Log.w(
                    TAG,
                    "reminder-rearm|ok=false|reason=$reason|stage=submit" +
                        "|type=${error.javaClass.simpleName}|message=${error.message}",
                )
            },
        )
    }

    private companion object {
        private const val TAG = "PIQ"
        private const val REASON_BOOT = "boot-completed"
        private const val REASON_TIMEZONE = "timezone-changed"
        private const val REASON_TIME_SET = "time-set"
    }
}

/**
 * "完成"闸门：无论重挂以哪种方式结束，[finish] 只被调用一次，且**成功返回后**才写
 * `async=done` 日志（R11.2 F06 / R13.1 F11）。
 *
 * 单列成 internal 类型是为了让 JVM 哨兵用**行为桩**钉住三条会判红的规则：
 *  - 重复完成只透传第一次（`compareAndSet` 换掉即红）；
 *  - `finish` 自身抛异常时 [log] 不执行、[onFinishFailure] 恰好一次、异常不外逃；
 *  - `finish` 成功返回后 [log] 才执行（两行对调即红）。
 *
 * 不再用源码文本/indexOf 守卫：把真实调用删掉、只留注释的形态无法骗过这里。
 */
internal class RearmFinishGate(
    private val finish: () -> Unit,
    private val log: (Boolean) -> Unit,
    private val onFinishFailure: (Throwable) -> Unit,
) {

    private val finished = AtomicBoolean(false)

    fun finish(ok: Boolean) {
        if (!finished.compareAndSet(false, true)) return
        try {
            finish()
        } catch (error: Throwable) {
            reportFailure(error)
            return
        }
        runCatching { log(ok) }.onFailure(::reportFailure)
    }

    private fun reportFailure(error: Throwable) {
        runCatching { onFinishFailure(error) }
    }
}

/**
 * 提交一次异步重挂，并把**同步抛出的**异常转成一次失败完成（R11.1 P3-3 / R11.2 F04）。
 *
 * [submit] 只负责把重挂任务交给执行器；它的回调被 [gate] 包成幂等。
 * 如果执行器在提交阶段就拒绝（`RejectedExecutionException`）或上下文为空，这里不会让
 * `PendingResult` 悬着：`onRejected` 在 `finally` 之前执行，即使它自身抛异常，仍然完成一次，
 * 且异常不逃出本函数（R11.2 F04）。
 */
internal fun startRearm(
    gate: RearmFinishGate,
    submit: (onDone: (Boolean) -> Unit) -> Unit,
    onRejected: (Throwable) -> Unit,
) {
    try {
        submit(gate::finish)
    } catch (error: Throwable) {
        try {
            onRejected(error)
        } catch (_: Throwable) {
            // 兜底回调自身失败时也必须完成；不能让它挡住 finish 或逃出接收器
        } finally {
            gate.finish(false)
        }
    }
}
