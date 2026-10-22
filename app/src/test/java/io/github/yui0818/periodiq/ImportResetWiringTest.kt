package io.github.yui0818.periodiq

import io.github.yui0818.periodiq.domain.import.ImportGate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 空 cycles 导入的重置分支（R13.1 F8 / R12 的 P2-05）。
 *
 * 场景：用户拿一份 `cycles: []` 的合法备份恢复
 *   Given 库里已有周期、预测与非空 model_params
 *   When  `POST /import` 通过 ImportGate 后落库
 *   Then  周期/预测为 0，`model_params` 回到空默认（不是保留上一份数据的 EMA）
 *
 * 行为读数在模拟器 `piq35` 上取（见报告 §3）；这里钉住生产分支的**结构**：
 * 非空分支才重算，空分支必须显式 `resetModelParams`。红路径：删掉 `else { resetModelParams }`
 * ⇒ 第 2 条红；把 reset 挪进非空分支 ⇒ 第 1/2 条同时红。
 */
class ImportResetWiringTest {

    private val router: String = ProductionSource.code("PiqRouter.kt")

    @Test
    fun `empty cycles take the reset branch instead of the recompute branch`() {
        val apply = functionBody(router, "private fun applyImport(")
        assertTrue("必须先按 cycles 是否为空分流", apply.contains("payload.cycles.isNotEmpty()"))
        assertTrue(
            "空分支必须显式重置 model_params",
            Regex("""else\s*\{\s*crud\.resetModelParams\(userId,\s*db\)\s*\}""").containsMatchIn(apply),
        )
        val resetAt = apply.indexOf("resetModelParams")
        val elseAt = apply.lastIndexOf("else", resetAt)
        val recomputeAt = apply.indexOf("fullRecomputeModelParams")
        assertTrue("重算必须在 reset 之前（即非空分支）", recomputeAt in 0 until resetAt)
        assertTrue("reset 必须落在 else 分支里，而不是无条件执行", elseAt > recomputeAt)
    }

    @Test
    fun `import always clears the previous rows before the branch`() {
        val apply = functionBody(router, "private fun applyImport(")
        val deleteCycles = apply.indexOf("deleteUserCycles")
        val deletePredictions = apply.indexOf("deleteUserPredictions")
        val branch = apply.indexOf("fullRecomputeModelParams")
        assertTrue("周期必须先清空", deleteCycles in 0 until branch)
        assertTrue("预测必须先清空", deletePredictions in 0 until branch)
    }

    @Test
    fun `the reset target is the documented empty defaults helper`() {
        val crud = ProductionSource.code("PiqCrud.kt")
        val reset = functionBody(crud, "fun resetModelParams(")
        assertTrue("reset 必须走同一条默认值 SQL", reset.contains("SQL_RESET_MODEL_PARAMS"))
        assertEquals(
            "reset 只该有一个实现入口",
            1,
            Regex("""fun\s+resetModelParams\(""").findAll(crud).count(),
        )
    }

    private fun functionBody(code: String, signature: String): String {
        val start = code.indexOf(signature)
        require(start >= 0) { "找不到 $signature，源守卫已失效" }
        val next = Regex("""(?m)^    (private |internal |fun |class |// =)""")
            .find(code, start + signature.length)?.range?.first
        return code.substring(start, next ?: code.length)
    }
}
