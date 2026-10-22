package io.github.yui0818.periodiq

import io.github.yui0818.periodiq.data.PiqCrud
import io.github.yui0818.periodiq.data.PiqDb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 建库文本的**逐字节**哨兵（R9.4 的 ③，台账 L3R93-REMAIN5 §5b 点名的那条必做验证）。
 *
 * 要防的事很具体：把 DDL 里的裸字面量接成 `${PiqConfig.*}` 之后，Kotlin 的渲染规则会让
 * `Long` 的 `7` 变成 `7.0`（写成 `Double` 或加了 `.toDouble()` 时）——值看着一样，
 * 但 `sqlite_master` 里的建库文本变了，`CHECK (cycle_length >= 7)` 与 `>= 7.0` 在 SQLite 里
 * 对 TEXT 亲和性的入参判定并不等价。台账里那份"文本等价干跑"是**镜像计算**（没跑 Kotlin），
 * 所以这条测试把它换成真读数：期望文本 = 接线**前**源码里的原文，接线**后**必须仍然逐字节命中。
 *
 * 判据定义：`PiqDb.DDL` 是生产建库时逐条 `execSQL` 的那份清单（`PiqDb.kt` 的 `DDL.forEach`），
 * 本测试不另造一份 SQL —— 造的就越离单源越远。
 */
class SchemaDefaultTextTest {

    private fun ddlOf(table: String): String =
        PiqDb.DDL.single { it.startsWith("CREATE TABLE IF NOT EXISTS $table ") }

    /** cycles 的 chk_length 上下界：接线自 `PiqConfig.MIN/MAX_CYCLE_DAYS`（Long），渲染不得带小数点。 */
    @Test
    fun `cycles chk_length keeps integer-rendered bounds`() {
        val text = ddlOf("cycles")
        assertTrue("下界必须渲染成 7，实测=[$text]",
            text.contains("cycle_length >= 7 AND cycle_length <= 90"))
        assertFalse("下界被渲染成 7.0 ⇒ 建库文本漂移", text.contains("cycle_length >= 7.0"))
        assertFalse("上界被渲染成 90.0 ⇒ 建库文本漂移", text.contains("cycle_length <= 90.0"))
    }

    /** model_params 的七个默认值：文本必须与接线前的裸字面量逐字节一致。 */
    @Test
    fun `model_params defaults keep the pre-wiring text`() {
        val text = ddlOf("model_params")
        listOf(
            "mean_cycle REAL NOT NULL DEFAULT 28.0",
            "std_cycle REAL NOT NULL DEFAULT 7.0",
            "n_records INTEGER NOT NULL DEFAULT 0",
            "ema_cycle REAL NOT NULL DEFAULT 28.0",
            "prior_mean REAL NOT NULL DEFAULT 28.0",
            "prior_std REAL NOT NULL DEFAULT 7.0",
            "prior_weight REAL NOT NULL DEFAULT 0.5",
        ).forEach { line ->
            assertTrue("缺这一行：[$line]\n实测=[$text]", text.contains(line))
        }
        // 渲染陷阱的阴性对照：Double 常量若被写成整数（0.5 → 0）或反过来，contains 会失配；
        // 这里再显式钉住"0.5 没有被渲染成 0.50 / 1.0"这两种漂移形态。
        assertFalse("prior_weight 渲染形态漂移", text.contains("DEFAULT 0.50"))
    }

    /**
     * `reset_model_params` 的 SQL 全文：期望串是**接线前源码里的原文**（R9.3 树 `PiqCrud.kt:355-358`），
     * 接线（改成拼 `PiqConfig.*`）之后必须逐字节相同。
     */
    @Test
    fun `reset model params sql is byte identical to the pre-wiring text`() {
        assertEquals(
            "UPDATE model_params SET mean_cycle = 28.0, std_cycle = 7.0, n_records = 0, ema_cycle = 28.0, " +
                "prior_mean = 28.0, prior_std = 7.0, prior_weight = 0.5, " +
                "updated_at = CURRENT_TIMESTAMP WHERE user_id = ?",
            PiqCrud.SQL_RESET_MODEL_PARAMS,
        )
    }
}
