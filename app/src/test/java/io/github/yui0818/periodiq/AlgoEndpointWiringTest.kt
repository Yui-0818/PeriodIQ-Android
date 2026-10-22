package io.github.yui0818.periodiq

import io.github.yui0818.periodiq.api.contract.Envelope

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 算法端点的 503 接线（R13.1 F10 / R12 的 P2-06）。
 *
 * 行为面（[AlgoGate] 两次尝试、失败后不再重试、503 映射）由 [AlgoGateTest] 覆盖；
 * 这里钉住“每个会调 Python 的端点都先过闸门”，以及“登录/资料写入不被算法故障牵连”。
 * 会同步调 `PiqAlgo` 的写路径另由 [AlgoWriteGateTest] 钉事务内顺序与条件。
 * 红路径：删掉任一算法处理函数里的 `requireAlgorithmReady()` ⇒ 对应用例红；
 * 把它挪到 `requireUser()` 之前 ⇒ 顺序断言红。
 */
class AlgoEndpointWiringTest {

    private val router: String = ProductionSource.code("PiqRouter.kt")

    private val algorithmHandlers = listOf(
        "private fun currentPrediction(): Envelope",
        "private fun accuracy(): Envelope",
        "private fun cycleStats(): Envelope",
        "private fun fertileWindow(): Envelope",
        "private fun exportPreview(): Envelope",
    )

    @Test
    fun `every algorithm handler checks the gate after the session`() {
        algorithmHandlers.forEach { signature ->
            val body = functionBody(router, signature)
            val userAt = body.indexOf("requireUser()")
            val gateAt = body.indexOf("requireAlgorithmReady()")
            assertTrue("$signature 必须在 requireUser 之后过算法闸门", userAt >= 0 && gateAt > userAt)
        }
    }

    @Test
    fun `the three debug endpoints are gated in the route table`() {
        val route = functionBody(router, "private fun route(")
        assertEquals("debug 透视镜三条必须各过一次闸门", 3, Regex("""requireAlgorithmReady\(\)""").findAll(route).count())
        assertEquals(
            "闸门调用应只出现在这 12 处（5 个处理函数 + 3 条 debug 分支 + 3 条直接写路径 + 1 处声明；" +
                "deleteCycle 走 runDeleteWithAlgorithmGate 条件闸门）",
            12,
            Regex("""requireAlgorithmReady\(\)""").findAll(router).count(),
        )
        assertEquals(
            "删除周期的条件闸门必须只接线一次",
            1,
            Regex("""runDeleteWithAlgorithmGate\(remaining, algoGate\)""").findAll(router).count(),
        )
    }

    @Test
    fun `the gate fails as a 503 with a readable code`() {
        val gate = functionBody(router, "private fun requireAlgorithmReady()")
        assertTrue(gate.contains("runAlgorithmEndpoint(algoGate)"))
        val error = algorithmUnavailable()
        assertEquals(503, error.status)
        assertEquals("ALGO_UNAVAILABLE", error.code)
        assertTrue("文案必须是人能读懂的整句", error.detail.isNotBlank() && error.detail.endsWith("。"))
    }

    @Test
    fun `login and profile writes do not consult the algorithm gate`() {
        listOf(
            "private fun login(body: JSONObject): Envelope",
            "private fun updateProfile(body: JSONObject): Envelope",
        ).forEach { signature ->
            val body = functionBody(router, signature)
            assertFalse("$signature 不得被算法故障牵连", body.contains("requireAlgorithmReady"))
        }
    }

    @Test
    fun `services build the gate once from the python starter`() {
        val services = ProductionSource.code("PiqServices.kt")
        assertTrue(
            "闸门必须从真实的 Python 启动器构造",
            Regex("""AlgoGate\(start\s*=\s*\{\s*PiqAlgo\.ensureStarted\(context\)\s*\}\)""").containsMatchIn(services),
        )
        assertEquals(
            "闸门必须只建一次并交给路由",
            1,
            Regex("""algoGate\s*=\s*AlgoGate\(""").findAll(services).count(),
        )
    }

    private fun functionBody(code: String, signature: String): String {
        val start = code.indexOf(signature)
        require(start >= 0) { "找不到 $signature，源守卫已失效" }
        val next = Regex("""(?m)^    (private |internal |fun |class |override |// =)""")
            .find(code, start + signature.length)?.range?.first
        return code.substring(start, next ?: code.length)
    }
}
