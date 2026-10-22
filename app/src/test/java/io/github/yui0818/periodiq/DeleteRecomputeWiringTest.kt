package io.github.yui0818.periodiq

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 删除路径必须重算派生字段（R14.2a.1）。
 *
 * `PiqRouter` 需要 Android SQLite，JVM 造不出来 ⇒ 沿用 [ProductionSource] 源守卫钉接线：
 * 删行后走 `recomputeAllRecords`，由它按“派生字段 → 模型/预测 → actual 回填”的顺序全量重算。
 */
class DeleteRecomputeWiringTest {

    private val router = ProductionSource.commentsRemoved("PiqRouter.kt")

    @Test
    fun `delete cycle recomputes derived fields through full recompute`() {
        val body = body("private fun deleteCycle(cycleId: Long): Envelope")
        val deleteAt = body.indexOf("crud.deleteCycle(")
        val recomputeAllAt = body.indexOf("recomputeAllRecords(")
        assertTrue(
            "删除路径漏了派生字段重算：deleteCycle 必须在删行后调用 recomputeAllRecords()",
            deleteAt >= 0 && recomputeAllAt > deleteAt,
        )
        assertFalse(
            "deleteCycle 不得直接调用 recomputeAfterDeleteTx()；应走 recomputeAllRecords 以包含派生字段重算",
            body.contains("recomputeAfterDeleteTx("),
        )
    }

    @Test
    fun `recomputeAllRecords keeps derived fields before model and actuals`() {
        val body = body("private fun recomputeAllRecords(")
        val derivedAt = body.indexOf("recomputeDerivedFields(")
        val afterDeleteAt = body.indexOf("recomputeAfterDeleteTx(")
        val backfillAt = body.indexOf("rebackfillPredictionActuals(")
        assertTrue("recomputeAllRecords 必须先重算派生字段，否则删除后 cycle_length 仍是旧值", derivedAt >= 0)
        assertTrue("recomputeAllRecords 必须包含 recomputeAfterDeleteTx", afterDeleteAt > derivedAt)
        assertTrue("recomputeAllRecords 必须在模型之后回填 actual", backfillAt > afterDeleteAt)
    }

    @Test
    fun `delete gate still runs before the row deletion`() {
        val body = body("private fun deleteCycle(cycleId: Long): Envelope")
        val writeAt = body.indexOf("store.write { db ->")
        val countAt = body.indexOf("getAllCyclesAsc(")
        val helperAt = body.indexOf("runDeleteWithAlgorithmGate(")
        val deleteAt = body.indexOf("crud.deleteCycle(")
        assertTrue("deleteCycle 必须在事务内先数剩余周期", writeAt >= 0 && countAt > writeAt)
        assertTrue("删除路径必须走剩余周期闸门策略", helperAt > countAt)
        assertTrue("闸门策略必须在删行之前", deleteAt > helperAt)
    }

    private fun body(signature: String): String = ProductionSource.functionBody("PiqRouter.kt", signature)
}
