package io.github.yui0818.periodiq

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 重挂完成闸门的 JVM 哨兵（R11.1 P3-3 / R11.2 F04/F06 / R13.1 F11/F12）。
 *
 * 这里全部用**行为桩**，不再用源码 `indexOf` 去猜一行代码的位置：
 *  - `finish` 抛异常 ⇒ 不写 `async=done`、走 `onFinishFailure`、异常不外逃；
 *  - 重复完成只透传第一次；
 *  - `finish` 成功返回后日志才执行；
 *  - `runRearmTask` 的 `onFinished(ok)` 被删掉时，本轮新增用例直接变红。
 *
 * 最后一条是唯一保留的源码守卫，只检查**接线**（`finish = pendingResult::finish` 与
 * `runRearmTask(` 各恰好一处）；它先经 [ProductionSource.code] 去掉注释与字符串再按行锚定，
 * 另有阴性对照证明注释里的同形文本不会命中。行为本身由上面的桩测试钉住。
 */
class PiqReminderReceiverTest {

    private class GateProbe {
        val events = mutableListOf<String>()
        val logs = mutableListOf<Boolean>()
        val failures = mutableListOf<Throwable>()
        var finishCalls = 0
        var finishThrows = false

        fun gate(): RearmFinishGate = RearmFinishGate(
            finish = {
                finishCalls += 1
                events += "finish"
                if (finishThrows) throw IllegalStateException("finish failed")
            },
            log = { ok ->
                events += "log"
                logs += ok
            },
            onFinishFailure = { failures += it },
        )
    }

    @Test
    fun `a synchronous submit failure still finishes the broadcast once`() {
        val probe = GateProbe()
        val rejected = mutableListOf<Throwable>()

        startRearm(
            gate = probe.gate(),
            submit = { throw RejectedExecutionException("executor shut down") },
            onRejected = { rejected += it },
        )

        assertEquals(1, probe.finishCalls)
        assertEquals(listOf(false), probe.logs)
        assertEquals(1, rejected.size)
        assertTrue(rejected.single() is RejectedExecutionException)
    }

    @Test
    fun `duplicate completion callbacks are collapsed to one finish`() {
        val probe = GateProbe()

        startRearm(
            gate = probe.gate(),
            submit = { onDone ->
                onDone(true)
                onDone(false)
            },
            onRejected = { error -> throw AssertionError("submit 不应抛异常", error) },
        )

        assertEquals(1, probe.finishCalls)
        assertEquals(listOf(true), probe.logs)
    }

    @Test
    fun `a throwing rejection handler still finishes once and does not escape`() {
        val probe = GateProbe()
        var rejected = 0

        startRearm(
            gate = probe.gate(),
            submit = { throw RejectedExecutionException("executor shut down") },
            onRejected = {
                rejected += 1
                throw IllegalStateException("log sink failed")
            },
        )

        assertEquals(1, rejected)
        assertEquals(1, probe.finishCalls)
        assertEquals(listOf(false), probe.logs)
    }

    @Test
    fun `a normal completion finishes once with success`() {
        val probe = GateProbe()

        startRearm(
            gate = probe.gate(),
            submit = { onDone -> onDone(true) },
            onRejected = { error -> throw AssertionError("submit 不应抛异常", error) },
        )

        assertEquals(1, probe.finishCalls)
        assertEquals(listOf(true), probe.logs)
        assertEquals(emptyList<Throwable>(), probe.failures)
    }

    @Test
    fun `gate calls finish before writing the async done log`() {
        val probe = GateProbe()

        probe.gate().finish(true)

        assertEquals(listOf("finish", "log"), probe.events)
    }

    @Test
    fun `a throwing finish is reported and does not escape or log done`() {
        val probe = GateProbe().apply { finishThrows = true }

        probe.gate().finish(true)

        assertEquals(1, probe.finishCalls)
        assertEquals(emptyList<Boolean>(), probe.logs)
        assertEquals(1, probe.failures.size)
        assertTrue(probe.failures.single() is IllegalStateException)
        assertFalse(probe.events.contains("log"))
    }

    @Test
    fun `a throwing finish in the synchronous failure path does not escape startRearm`() {
        val probe = GateProbe().apply { finishThrows = true }

        startRearm(
            gate = probe.gate(),
            submit = { throw RejectedExecutionException("executor shut down") },
            onRejected = { },
        )

        assertEquals(1, probe.finishCalls)
        assertEquals(emptyList<Boolean>(), probe.logs)
        assertEquals(1, probe.failures.size)
    }

    @Test
    fun `runRearmTask reports success after the action completes`() {
        val finished = mutableListOf<Boolean>()
        var actions = 0

        runRearmTask(
            executor = Executor { it.run() },
            action = { actions += 1 },
            onFinished = { finished += it },
        )

        assertEquals(1, actions)
        assertEquals(listOf(true), finished)
    }

    @Test
    fun `runRearmTask reports failure when the action throws`() {
        val finished = mutableListOf<Boolean>()
        val actionFailures = mutableListOf<Throwable>()
        val finishFailures = mutableListOf<Throwable>()

        runRearmTask(
            executor = Executor { it.run() },
            action = { throw IllegalStateException("rearm failed") },
            onFinished = { finished += it },
            onActionFailure = { actionFailures += it },
            onFinishFailure = { finishFailures += it },
        )

        assertEquals(listOf(false), finished)
        assertEquals(1, actionFailures.size)
        assertEquals(emptyList<Throwable>(), finishFailures)
    }

    @Test
    fun `runRearmTask contains a throwing callback and reports finish failure`() {
        val finishFailures = mutableListOf<Throwable>()

        runRearmTask(
            executor = Executor { it.run() },
            action = { },
            onFinished = { throw IllegalStateException("finish callback failed") },
            onFinishFailure = { finishFailures += it },
        )

        assertEquals(1, finishFailures.size)
        assertTrue(finishFailures.single() is IllegalStateException)
    }

    @Test
    fun `production wires the real pending result and the callback runner`() {
        val receiver = ProductionSource.code("PiqReminderReceiver.kt")
        val scheduler = ProductionSource.code("PiqReminderScheduler.kt")

        assertEquals(
            "生产接收器必须把真实的 PendingResult.finish 交给闸门",
            1,
            Regex("""(?m)^\s*finish\s*=\s*pendingResult::finish\s*,?\s*$""").findAll(receiver).count(),
        )
        assertEquals(
            "rearmFromStoreAsync 必须走 runRearmTask（否则删掉 onFinished(ok) 无人拦截）",
            1,
            Regex("""(?m)^\s*runRearmTask\(\s*$""").findAll(scheduler).count(),
        )
    }

    /**
     * 守卫本身的阴性对照（R13.1 F12）：同一段接线写进注释或字符串时，去噪后的读数必须为 0。
     * 若 `ProductionSource.code` 退回裸文本，本条立刻红。
     */
    @Test
    fun `the source guard ignores commented out and stringified wiring`() {
        val lookalike = """
            /*
            finish = pendingResult::finish
            */
            val note = ""${'"'}
            finish = pendingResult::finish
            ""${'"'}
        """.trimIndent()
        val code = ProductionSource.strip(lookalike)
        assertEquals(
            "注释/字符串里的接线不得被当成生产接线",
            0,
            Regex("""(?m)^\s*finish\s*=\s*pendingResult::finish\s*,?\s*$""").findAll(code).count(),
        )
        assertEquals(
            "对照：不去噪时这两处同形文本必须被数到（否则本用例证明不了去噪器有用）",
            2,
            Regex("""(?m)^\s*finish\s*=\s*pendingResult::finish\s*,?\s*$""").findAll(lookalike).count(),
        )
        assertTrue(
            "去噪后仍应保留真正的代码行",
            ProductionSource.strip("finish = pendingResult::finish").contains("finish = pendingResult::finish"),
        )
    }
}
