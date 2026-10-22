package io.github.yui0818.periodiq

import io.github.yui0818.periodiq.api.contract.ApiError
import io.github.yui0818.periodiq.api.contract.Envelope

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会同步调用 [PiqAlgo] 的写路径必须过算法闸门（R13.1.1 F10 / R13.1 审查 R01）。
 *
 * 判据：闸门在 `store.write` 事务内、首个数据变更与首个 `PiqAlgo` 调用之前；
 * 只在真正需要算法时触发（改开始日、非空导入）；登录、资料、仅改备注不查闸门。
 * 完整 `PiqRouter` 需要 Android SQLite，JVM 造不出来 ⇒ 用顺序与条件的源码守卫钉接线。
 *
 * 判红手段：删掉任一写路径的闸门、把它挪出 `store.write`、去掉
 * `startChanged` / `payload.cycles.isNotEmpty()` / `remainingAfterDelete > 0` 条件，
 * 对应用例都会红。
 */
class AlgoWriteGateTest {

    private val router = ProductionSource.commentsRemoved("PiqRouter.kt")

    @Test
    fun `create cycle gates inside the transaction before touching rows`() {
        val body = body("private fun createCycle(body: JSONObject): Envelope")
        val writeAt = body.indexOf("store.write { db ->")
        val gateAt = body.indexOf("requireAlgorithmReady()")
        assertTrue("createCycle 必须有闸门", gateAt >= 0)
        assertTrue("闸门必须在写事务内", writeAt >= 0 && gateAt > writeAt)
        val firstMutation = body.indexOf("crud.createCycle(")
        val firstAlgo = body.indexOf("runPostCycleChain(")
        assertTrue("找不到周期写入调用，源守卫已失效", firstMutation >= 0 && firstAlgo >= 0)
        assertTrue("闸门必须在首个数据变更之前", gateAt < firstMutation)
        assertTrue("闸门必须在首个算法调用之前", gateAt < firstAlgo)
        val endCheckAt = body.indexOf("INVALID_PERIOD_END")
        val overlapAt = body.indexOf("checkDateOverlap(")
        assertTrue("找不到结束日期 400 校验，源守卫已失效", endCheckAt >= 0)
        assertTrue("找不到重叠校验，源守卫已失效", overlapAt >= 0)
        assertTrue("闸门必须在结束日期 400 校验之后（400 优先）", gateAt > endCheckAt)
        assertTrue("闸门必须在重叠校验之后（400 优先）", gateAt > overlapAt)
    }

    @Test
    fun `delete cycle counts remaining rows and gates only when some remain`() {
        val body = body("private fun deleteCycle(cycleId: Long): Envelope")
        val writeAt = body.indexOf("store.write { db ->")
        val countAt = body.indexOf("getAllCyclesAsc(")
        val helperAt = body.indexOf("runDeleteWithAlgorithmGate(")
        val deleteAt = body.indexOf("crud.deleteCycle(")
        assertTrue("deleteCycle 必须在事务内先数剩余周期", writeAt >= 0 && countAt > writeAt)
        assertTrue("deleteCycle 必须走剩余周期策略", helperAt >= 0 && helperAt > countAt)
        assertTrue("策略调用必须在删行之前", deleteAt >= 0 && helperAt < deleteAt)
        val helper = body("internal fun <T> runDeleteWithAlgorithmGate(")
        assertTrue(
            "只有删完还剩周期才查闸门",
            Regex("""if\s*\(\s*remainingAfterDelete\s*>\s*0\s*\)\s*runAlgorithmEndpoint""").containsMatchIn(helper),
        )
    }

    @Test
    fun `deleting the last cycle is not gated but deleting one with remaining cycles is`() {
        // router 在事务返回后统一构造 200；这里断言不抛 503 且删除体执行。
        val lastGate = AlgoGate(start = { false })
        var lastDeleted = false
        runDeleteWithAlgorithmGate(0, lastGate) { lastDeleted = true }
        assertTrue("删最后一条周期不得被算法闸门拦住", lastDeleted)
        assertEquals("删最后一条周期不得尝试启动算法", 0, lastGate.attempts())

        val remainingGate = AlgoGate(start = { false })
        var remainingDeleted = false
        val err = assertThrows(ApiError::class.java) {
            runDeleteWithAlgorithmGate(1, remainingGate) { remainingDeleted = true }
        }
        assertEquals(503, err.status)
        assertEquals("ALGO_UNAVAILABLE", err.code)
        assertFalse("503 时不得执行删除体（行保持不变）", remainingDeleted)
        assertEquals("有剩余周期时必须查一次闸门", 1, remainingGate.attempts())
    }

    @Test
    fun `changing the start date gates before recompute and mutation`() {
        val body = body("private fun updateCycle(cycleId: Long, body: JSONObject): Envelope")
        val conditional = Regex("""if\s*\(\s*startChanged\s*\)\s*requireAlgorithmReady\(\)""")
        assertTrue("只有开始日变更才查闸门", conditional.containsMatchIn(body))
        val gateAt = body.indexOf("requireAlgorithmReady()")
        assertTrue("闸门必须在写事务内", gateAt > body.indexOf("store.write { db ->"))
        assertTrue("闸门必须在重算之前", gateAt < body.indexOf("recomputeAllRecords("))
        assertTrue("闸门必须在更新行之前", gateAt < body.indexOf("crud.updateCycle("))
        assertEquals("updateCycle 只能有一处闸门", 1, Regex("""requireAlgorithmReady\(\)""").findAll(body).count())
    }

    @Test
    fun `non-empty import gates before clearing existing rows`() {
        val body = body("private fun applyImport(userId: Long, payload: ImportPayload): Pair<Int, Int> = store.write { db ->")
        val conditional = Regex("""if\s*\(\s*payload\.cycles\.isNotEmpty\(\)\s*\)\s*requireAlgorithmReady\(\)""")
        assertTrue("只有非空导入才查闸门", conditional.containsMatchIn(body))
        val gateAt = body.indexOf("requireAlgorithmReady()")
        assertTrue("闸门必须在清空周期之前", gateAt < body.indexOf("crud.deleteUserCycles("))
        assertTrue("闸门必须在清空预测之前", gateAt < body.indexOf("crud.deleteUserPredictions("))
        assertTrue("闸门必须在全量重算之前", gateAt < body.indexOf("fullRecomputeModelParams("))
        assertTrue("空导入仍走重置分支", body.indexOf("crud.resetModelParams(") > body.indexOf("else {"))
    }

    @Test
    fun `auth and profile writes never consult the gate`() {
        listOf(
            "private fun login(body: JSONObject): Envelope",
            "private fun updateProfile(body: JSONObject): Envelope",
        ).forEach { signature ->
            assertFalse("$signature 不得被算法故障牵连", body(signature).contains("requireAlgorithmReady"))
        }
    }

    private fun body(signature: String): String = ProductionSource.functionBody("PiqRouter.kt", signature)
}
