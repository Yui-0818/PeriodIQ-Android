package io.github.yui0818.periodiq

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService

/**
 * 排程需要的两件外部事实，由宿主实现、排程层消费。
 *
 * 这两件事都不许在排程层里自己去问：预计开始日如果经由路由/算法拿，就会把 Chaquopy 拖进
 * "改一个开关"这条路径；登录态如果自己去解令牌，就会和 [SessionHolder] 形成两套口径。
 */
interface ReminderSource {
    /** 当前是否有有效会话（[PiqRouter.requireUser] 用的同一条 SQL：令牌解得出用户才算登录）。 */
    fun isLoggedIn(): Boolean

    /** 最新一条预测的 `predicted_date`（契约 §1 的中心估计值）；没有预测或未登录时返回 null。 */
    fun predictedStart(): LocalDate?
}

/**
 * 宿主侧要替提醒层做的三件事（实现方：[MainActivity]）。
 *
 * 之所以做成接口而不是让桥直接调 [PiqReminderScheduler]：权限框必须由 Activity 发起，
 * 而设置写入跑在 JS 桥线程上 —— 中间需要一个"回到主线程"的人。
 */
interface PiqReminderHooks {
    fun notificationPermissionGranted(): Boolean

    /** 只在"用户亲手打开总开关"这一条路径上调用；结果回调里由调用方决定回弹还是重排。 */
    fun requestNotificationPermission(onResult: (granted: Boolean) -> Unit)

    fun rescheduleAsync(reason: String)

    fun cancelAllAsync(reason: String)
}

/** 一次 API 服务完之后，提醒层该做的动作。 */
enum class ReminderAction { RESCHEDULE, CANCEL_ALL, NONE }

/**
 * 重排触发点的判定：**纯函数**，输入是 REST 的方法 + 绝对路径 + 响应状态。
 *
 * 覆盖提示词 §4 列的全部七条腿：启动（拿到登录态之后）· 周期增删改 · 导入 ·
 * 登录后首次拿到预测 · 设置变更 · 开机 · 时区/时间变化。其中前四条都表现为"某条请求被服务过"，
 * 所以统一走 [PiqApiGateway] 这一个漏斗；后三条由 [PiqReminderReceiver] 直接触发。
 * 设置变更走 [HostSettingsBridge]。
 *
 * **状态分层**（R11.1 P3-2 / R11.2 F01/F07/F08 / R13.1 F12）：
 *  - `path` 先去掉 `?query` 再判前缀（与 [PiqRouter] 同口径）；`/api/v1` 本身、仅斜杠的
 *    `/api/v1/` 根、`/api/v2/...`、静态资源与其它命名空间一律 [ReminderAction.NONE]；
 *  - 尾斜杠**只剥一个**：`/auth/logout/` 视为别名，`/auth/logout//` 不是端点；
 *    周期写操作只认 `/cycles/<单段 id>`，`/cycles//` 与 `/cycles/7/` 不重排（R13.1 F12）；
 *  - `401` 单独处理：登录 / 注册的 401 是**凭证层面的拒绝**（例如输错密码），此时并没有
 *    "已登录又失效"的会话，撤提醒只会误清用户已设好的提醒 ⇒ [ReminderAction.NONE]；
 *    其余 `/api/v1/` 路径的 401 表示会话失效 ⇒ 隐私口径下 [ReminderAction.CANCEL_ALL]；
 *  - 其它 `≥400`（400 / 403 / 404 / 500 / 501 …）一律 [ReminderAction.NONE]：一次失败的写操作
 *    不该改闹钟；
 *  - `method` 先 `uppercase()` 再判 `GET`（小写 `get /cycles/<id>` 不得被当成写操作）；
 *  - 成功响应（`<400`）再按下面两个集合判定重排或全取消。
 */
object PiqReminderRoutes {

    private const val PREFIX = "/api/v1"
    private const val API_PATH_PREFIX = "$PREFIX/"
    private const val CYCLES = "/cycles"
    private val CYCLE_ITEM = Regex("""^/cycles/[^/]+$""")
    private const val PREDICTION_CURRENT = "/predictions/current"

    /** 会话失效的 HTTP 状态（配合 [UNAUTHENTICATED_REST] 使用）。 */
    private const val HTTP_UNAUTHORIZED = 401

    /** 会让"预计开始日"或登录态改变，因而需要重排的写端点与首屏端点。 */
    private val RESCHEDULE_REST = setOf(
        "/auth/login",
        "/auth/register",
        "/auth/me",
        PREDICTION_CURRENT,
        CYCLES,
        "/import",
    )

    /**
     * 会话结束：隐私口径下锁屏上不该还留着经期信息 ⇒ 全取消。
     *
     * `/auth/change-password` **不在这里**（R11.1 P3-1）：后端改密只删除**其它**会话，
     * 发起改密的当前会话仍然有效；提醒是**设备级偏好**，与密码无关。把它当成会话结束
     * 会把"用户改个密码"变成"提醒全没了"。
     */
    private val CANCEL_ALL_REST = setOf(
        "/auth/logout",
        "/auth/delete-account",
    )

    /** 401 但**不代表**会话结束的两条：被拒说明"还没登录成功"，不是"登录过又失效了"。 */
    private val UNAUTHENTICATED_REST = setOf(
        "/auth/login",
        "/auth/register",
    )

    fun action(method: String, path: String, status: Int): ReminderAction {
        val cleanPath = path.substringBefore('?')
        if (!cleanPath.startsWith(API_PATH_PREFIX)) return ReminderAction.NONE
        val rawRest = cleanPath.removePrefix(PREFIX)
        if (rawRest.isEmpty() || rawRest.all { it == '/' }) return ReminderAction.NONE
        // 只剥**一个**尾斜杠：/logout/ 是别名，/logout// 不是端点，不能把多重斜杠归一成端点。
        val rest = if (rawRest.endsWith("/") && !rawRest.endsWith("//")) rawRest.dropLast(1) else rawRest
        if (rest.isEmpty() || rest.all { it == '/' }) return ReminderAction.NONE
        val verb = method.uppercase()
        if (status == HTTP_UNAUTHORIZED) {
            return if (rest in UNAUTHENTICATED_REST) ReminderAction.NONE else ReminderAction.CANCEL_ALL
        }
        if (status >= 400) return ReminderAction.NONE
        if (rest in CANCEL_ALL_REST) return ReminderAction.CANCEL_ALL
        if (rest in RESCHEDULE_REST) return ReminderAction.RESCHEDULE
        // 周期写操作只认 /cycles/<单段 id>；/cycles/7/ 与 /cycles// 不是端点（R13.1 F12）
        if (CYCLE_ITEM.matches(rawRest) && verb != "GET") {
            return ReminderAction.RESCHEDULE
        }
        return ReminderAction.NONE
    }
}

/**
 * 提醒层的装配与落地（R11）：把 [PiqReminderPlan] 算出的清单变成**落盘计划 + AlarmManager 闹钟**，
 * 并在到点时把落盘计划变成通知。
 *
 * **三条纪律**（都来自提示词 §2② / §4）：
 *  1. 排程结果先落盘（[PiqReminderStore]），发通知那一步只读盘 —— 不起 Python、不碰 WebView；
 *  2. 四类闹钟各用独立 requestCode 与独立通知 id，取消某一类只动那一类（互不连带）；
 *  3. `AlarmManager.setAndAllowWhileIdle(RTC_WAKEUP, …)`：**非精确**闹钟，不引 `androidx.work`，
 *     不引任何第三方依赖。
 *
 * 所有公开入口都不要求调用线程是主线程；真正的读写排在单线程执行器里做，
 * 因此 `[ReminderSource]` 的实现可以安全地查数据库（主线程查库是 [PiqServices] 明令禁止的）。
 */
object PiqReminderScheduler {

    private const val TAG = "PIQ"

    /** 到点广播（本应用内部显式意图，接收器 `exported=false`）。 */
    const val ACTION_REMIND = "io.github.yui0818.periodiq.action.REMIND"

    /** 随意图带过去的通知 id，接收器按它去落盘计划里取文案。 */
    const val EXTRA_NOTIFY_ID = "notify_id"

    /** 渠道名（用户可见，设置页通知分组里显示）。 */
    const val CHANNEL_NAME = "经期提醒"

    /**
     * 安全锁屏上显示的**公开版本**标题（逐字，R11.1 P3-4）。
     *
     * 与 [CHANNEL_NAME] 同字是巧合，不合并：渠道名改文案时公开版本不该跟着变。
     */
    const val PUBLIC_VERSION_TITLE = "经期提醒"

    /** 安全锁屏上显示的**公开版本**正文（逐字）：不带日期、不带任何真实提醒事实。 */
    const val PUBLIC_VERSION_BODY = "最近注意带上必需品哦"

    /** 权限请求码（[MainActivity] 的 onRequestPermissionsResult 用它认领回调）。 */
    const val PERMISSION_REQUEST_CODE = 4311

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "piq-reminders").apply { isDaemon = true }
    }

    /** API 33 起通知是运行时权限；32 及以下没有这个权限，开关直接可用（提示词 §2④）。 */
    fun permissionGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** 异步重排：读五条设置 + 登录态 + 预计开始日 ⇒ [PiqReminderPlan.plan] ⇒ 落盘 + 挂/撤闹钟。 */
    fun rescheduleAsync(context: Context, source: ReminderSource, reason: String) {
        val app = context.applicationContext
        executor.execute {
            runCatching { rescheduleNow(app, source, reason) }
                .onFailure { Log.w(TAG, "reminder-reschedule|ok=false|reason=$reason|type=${it.javaClass.simpleName}|message=${it.message}") }
        }
    }

    /** 异步全取消（关总开关 / 登出 / 删号 / 权限被拒）。 */
    fun cancelAllAsync(context: Context, reason: String) {
        val app = context.applicationContext
        executor.execute {
            runCatching { cancelAllNow(app, reason) }
                .onFailure { Log.w(TAG, "reminder-cancel|ok=false|reason=$reason|type=${it.javaClass.simpleName}|message=${it.message}") }
        }
    }

    /**
     * 开机 / 时区变化 / 时间被改之后按**落盘计划**重挂。
     *
     * 这里**故意不查库**：接收器所在的过程没有 WebView、也不该为挂闹钟去起 Python。
     * 计划里存的是**本地触发日 + 时区 id**，所以时区变化后按**当时的**默认时区重新求 09:00
     * 就是提示词要的"时区变化后按新时区重算"；已经过时的条目自然不再挂。
     *
     * [onFinished] 在重挂**成功或失败之后**由本执行器线程调用一次（R11.1 P3-3）：
     * 三条系统广播的接收器要靠它调用 `PendingResult.finish()`。回调自身抛异常时记一条
     * `stage=finish` 的失败日志 —— 那时 `finish()` 没有被调到，问题必须留在 logcat 里而不是静默。
     */
    fun rearmFromStoreAsync(context: Context, reason: String, onFinished: (Boolean) -> Unit) {
        val app = context.applicationContext
        runRearmTask(
            executor = executor,
            action = { rearmNow(app, reason) },
            onFinished = onFinished,
            onActionFailure = { error ->
                Log.w(TAG, "reminder-rearm|ok=false|reason=$reason|type=${error.javaClass.simpleName}|message=${error.message}")
            },
            onFinishFailure = { error ->
                Log.w(TAG, "reminder-rearm|ok=false|reason=$reason|stage=finish|type=${error.javaClass.simpleName}|message=${error.message}")
            },
        )
    }

    /** 同步重排（调用线程自行负责：不得是主线程）。[rescheduleAsync] 是唯一生产调用点。 */
    fun rescheduleNow(context: Context, source: ReminderSource, reason: String) {
        val settings = PiqSettings(context)
        val options = settings.notifyOptions(loggedIn = source.isLoggedIn())
        val predictedStart = source.predictedStart()
        val plan = PiqReminderPlan.plan(options, predictedStart, ZonedDateTime.now())
        val days = options.days.takeIf { it in PiqReminderPlan.VALID_DAYS } ?: PiqReminderPlan.DEFAULT_DAYS
        PiqReminderStore.write(context, predictedStart, days, plan.alarms)
        applyAlarms(context, plan.alarms)
        Log.i(
            TAG,
            "reminder-reschedule|reason=$reason|master=${options.masterEnabled}|advance=${options.advanceEnabled}" +
                "|onDay=${options.onDayEnabled}|overdue=${options.overdueEnabled}|days=$days" +
                "|hasPrediction=${predictedStart != null}|armed=${plan.alarms.map { it.notifyId }}" +
                "|skipped=${plan.skipped}|pid=${android.os.Process.myPid()}",
        )
    }

    /** 全取消：撤下四类闹钟与四条可能已挂的通知，并把落盘计划写成空清单。 */
    fun cancelAllNow(context: Context, reason: String) {
        applyAlarms(context, emptyList())
        PiqReminderStore.clear(context)
        Log.i(TAG, "reminder-cancel|reason=$reason|alarms=0|ids=${PiqReminderPlan.ALL_NOTIFY_IDS}")
    }

    /** 同步重挂（执行器线程）。 */
    fun rearmNow(context: Context, reason: String) {
        val snapshot = PiqReminderStore.read(context)
        if (snapshot.alarms.isEmpty()) {
            applyAlarms(context, emptyList())
            Log.i(TAG, "reminder-rearm|reason=$reason|stored=0|armed=0|note=empty-plan")
            return
        }
        val nowMillis = System.currentTimeMillis()
        val rearmed = snapshot.alarms.filter { epochOf(it) > nowMillis }
        PiqReminderStore.write(context, snapshot.predictedStart, snapshot.days, rearmed)
        applyAlarms(context, rearmed)
        Log.i(
            TAG,
            "reminder-rearm|reason=$reason|stored=${snapshot.alarms.size}|armed=${rearmed.size}" +
                "|ids=${rearmed.map { it.notifyId }}|zone=${ZoneId.systemDefault().id}",
        )
    }

    /** 一条腿：通知 id 与它的 requestCode 一一对应，谁也不能替谁。 */
    private data class Leg(val notifyId: Int, val requestCode: Int)

    /** 全部四条腿。取消某一类 = 只把不在清单里的那几条撤掉，其余原样留着。 */
    private val LEGS: List<Leg> = listOf(
        Leg(PiqReminderPlan.NOTIFY_ID_ADVANCE, PiqReminderPlan.REQUEST_CODE_ADVANCE),
        Leg(PiqReminderPlan.NOTIFY_ID_ON_DAY, PiqReminderPlan.REQUEST_CODE_ON_DAY),
        Leg(PiqReminderPlan.NOTIFY_ID_OVERDUE_FIRST, PiqReminderPlan.REQUEST_CODE_OVERDUE_FIRST),
        Leg(PiqReminderPlan.NOTIFY_ID_OVERDUE_SECOND, PiqReminderPlan.REQUEST_CODE_OVERDUE_SECOND),
    )

    /**
     * 让"当前该挂的清单"成为唯一事实：清单里的腿重新挂上，清单外的腿把闹钟与已挂通知都撤掉。
     *
     * 三类互不连带这个性质由两处共同保证：[PiqReminderPlan.plan] 逐条产出 + 这里逐条撤。
     * 触发时刻一律按**当前默认时区**的本地 09:00 重算，所以时区变化不需要额外分支。
     */
    private fun applyAlarms(context: Context, alarms: List<PiqReminderPlan.Alarm>) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        ensureChannel(context)
        val armed = alarms.associateBy { it.notifyId }
        LEGS.forEach { leg ->
            val alarm = armed[leg.notifyId]
            if (alarm == null) {
                alarmManager.cancel(pendingIntentFor(context, leg.requestCode, leg.notifyId))
                notificationManager.cancel(leg.notifyId)
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    epochOf(alarm),
                    pendingIntentFor(context, leg.requestCode, leg.notifyId),
                )
            }
        }
    }

    /** 落盘条目 → 绝对时刻：按**当时**的默认时区重新求本地 09:00（落盘里的 epoch 只作留证）。 */
    private fun epochOf(alarm: PiqReminderPlan.Alarm): Long =
        PiqReminderPlan.triggerAtLocalNine(alarm.triggerDate, ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    private fun pendingIntentFor(context: Context, requestCode: Int, notifyId: Int): PendingIntent {
        val intent = Intent(context, PiqReminderReceiver::class.java)
            .setAction(ACTION_REMIND)
            .putExtra(EXTRA_NOTIFY_ID, notifyId)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** 渠道只需建一次；重复创建是系统的幂等语义（渠道已存在时不会重置用户已设的渠道偏好）。 */
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(PiqReminderPlan.CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                PiqReminderPlan.CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "经期预测的提前、当日与逾期提醒" },
        )
        Log.i(TAG, "reminder-channel|id=${PiqReminderPlan.CHANNEL_ID}|created=true")
    }

    /**
     * 到点了：只读落盘计划，按通知 id 取文案发出去。
     *
     * 不查库、不起 Python、不碰 WebView（提示词 §3 第 3 条）。计划里没有这一条 ⇒ 什么都不发，
     * 并把这条挂账记进日志（多半是取消与广播之间的一次竞态）。
     */
    fun fireNotification(context: Context, notifyId: Int) {
        val alarm = PiqReminderStore.read(context).alarms.firstOrNull { it.notifyId == notifyId }
        if (alarm == null) {
            Log.w(TAG, "reminder-fire|id=$notifyId|posted=false|reason=not-in-plan")
            return
        }
        if (!permissionGranted(context)) {
            Log.w(TAG, "reminder-fire|id=$notifyId|posted=false|reason=no-notification-permission")
            return
        }
        ensureChannel(context)
        val openApp = PendingIntent.getActivity(
            context,
            notifyId,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_NOTIFY_ID, notifyId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, PiqReminderPlan.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_periodiq_reminder)
            .setContentTitle(alarm.title)
            .setContentText(alarm.body)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            // 真实文案只属于真实通知；安全锁屏交给公开版本（受系统「锁屏显示敏感内容」设置控制）。
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(context, openApp))
            .build()
        context.getSystemService(NotificationManager::class.java).notify(alarm.notifyId, notification)
        Log.i(
            TAG,
            "reminder-fire|id=${alarm.notifyId}|posted=true|hasPrediction=true" +
                "|channel=${PiqReminderPlan.CHANNEL_ID}|visibility=private|pid=${android.os.Process.myPid()}",
        )
    }

    /**
     * 安全锁屏（已设 PIN / 图案 / 密码；系统未允许「锁屏显示敏感内容」时）只显示这一条（[fireNotification] 用 `setPublicVersion` 挂上）。
     *
     * 文案固定为 [PUBLIC_VERSION_TITLE] / [PUBLIC_VERSION_BODY]：不含预计日期、不含
     * "距离预计经期 / 预计今天开始经期 / 预计经期已过" 这类真实事实 ⇒ 旁人看到锁屏也不知道周期。
     * 公开版本复用真实通知的同一个 `contentIntent`，点它仍然回到 App；不新增 Activity。
     */
    private fun publicVersion(context: Context, contentIntent: PendingIntent): Notification =
        Notification.Builder(context, PiqReminderPlan.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_periodiq_reminder)
            .setContentTitle(PUBLIC_VERSION_TITLE)
            .setContentText(PUBLIC_VERSION_BODY)
            .setContentIntent(contentIntent)
            .build()
}

/**
 * “执行重挂 + 回调”的可注入纯函数（R13.1 F11 / R11.2-R04）。
 *
 * 生产执行器与 JVM 测试用的直接执行器走同一条代码路径：
 * `action` 成功/失败 ⇒ `onFinished(ok)` **恰好一次**；回调自身抛异常 ⇒ 只走
 * `onFinishFailure`，不逃出执行器线程。删除 `onFinished(ok)` 会让
 * `PiqReminderReceiverTest` 的 `runRearmTask` 用例直接变红。
 */
internal fun runRearmTask(
    executor: Executor,
    action: () -> Unit,
    onFinished: (Boolean) -> Unit,
    onActionFailure: (Throwable) -> Unit = {},
    onFinishFailure: (Throwable) -> Unit = {},
) {
    executor.execute {
        val ok = runCatching { action() }
            .onFailure(onActionFailure)
            .isSuccess
        runCatching { onFinished(ok) }
            .onFailure(onFinishFailure)
    }
}
