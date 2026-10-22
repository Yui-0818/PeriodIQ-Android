package io.github.yui0818.periodiq

import io.github.yui0818.periodiq.api.contract.ApiError

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 算法可用性闸门（R13.1 F10）。
 *
 * 判据：
 *  - 首次启动失败后，同进程最多再试一次；第三次调用不再触发 `start`；
 *  - 第二次成功则恢复 true；
 *  - 不可用错误是 503 `ALGO_UNAVAILABLE`，不是 500。
 *
 * 判红手段：把 [AlgoGate.ensure] 的 `attempts >= maxAttempts` 删掉 ⇒ 第三条用例的
 * `calls` 会变成 3；把 `algorithmUnavailable` 的状态改成 500 ⇒ 第二条用例红。
 */
class AlgoGateTest {

    @Test
    fun `a failed start is retried only once per process`() {
        var calls = 0
        val gate = AlgoGate(start = { calls += 1; false })

        assertFalse(gate.ensure())
        assertFalse(gate.ensure())
        assertFalse(gate.ensure())

        assertEquals("失败后只允许再试一次（总共 2 次）", 2, calls)
        assertEquals(2, gate.attempts())
    }

    @Test
    fun `a retry can recover the gate`() {
        var calls = 0
        val gate = AlgoGate(start = { calls += 1; calls >= 2 })

        assertFalse(gate.ensure())
        assertTrue(gate.ensure())
        assertTrue(gate.ensure())

        assertEquals(2, calls)
    }

    @Test
    fun `unavailable maps to a readable 503 with the contract code`() {
        val error = algorithmUnavailable()

        assertEquals(503, error.status)
        assertEquals("ALGO_UNAVAILABLE", error.code)
        assertTrue("文案必须是人能读懂的句子", error.detail.length >= 12)
    }

    /**
     * S-AND-R13.1-ALGO-503 的 JVM 腿：假 gate 在启动期已失败，再调任一算法端点时
     * 闸门必须给 503 且**业务体一行都不执行**。完整 router 需要 Android SQLite 桩类，
     * 这里钉的是每个算法端点都必经的同一个函数（接线另由 AlgoEndpointWiringTest 守住）。
     */
    @Test
    fun `a failed gate answers 503 and never runs the endpoint body`() {
        val gate = AlgoGate(start = { false })
        assertFalse("先模拟启动期失败", gate.ensure())
        var bodyRan = false

        val error = runCatching {
            runAlgorithmEndpoint(gate) { bodyRan = true; 200 }
        }.exceptionOrNull()

        assertTrue("必须是 ApiError，实测=$error", error is ApiError)
        assertEquals(503, (error as ApiError).status)
        assertEquals("ALGO_UNAVAILABLE", error.code)
        assertFalse("闸门失败时业务体不得执行", bodyRan)
    }

    @Test
    fun `the gate exposes no unused readiness flag and reuses the attempt constant`() {
        val code = ProductionSource.code("PiqApiCore.kt")
        assertFalse("isReady 没有生产调用方，不得保留死代码", code.contains("fun isReady"))
        assertTrue(
            "重试上限的默认值必须引用 MAX_ATTEMPTS",
            Regex("""maxAttempts\s*:\s*Int\s*=\s*MAX_ATTEMPTS""").containsMatchIn(code),
        )
    }
}
