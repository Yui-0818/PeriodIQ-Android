package io.github.yui0818.periodiq

import io.github.yui0818.periodiq.data.PiqCrud

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Python 语义兼容层哨兵（R3 F1）。
 *
 * 期望值全部来自桌面解释器实测（同一份 `export_source_vectors.py` 导出的
 * `.dsh/r3_probe/source_vectors.json` 的 `pymath` 段），**逐条原文照搬**：
 * 这批向量的价值就在于它们覆盖了"朴素 Java 写法必错"的样本
 * （`round(2.675, 2)`、`round(0.5)`、五取偶、`median` 偶数长度取均值、代理年 2 月末的日差）。
 */
class PyMathCompatTest {

    @Test
    fun `roundTo matches python round for every exported vector`() {
        ROUND_VECTORS.forEach { (value, digits, expect) ->
            assertEquals("round($value, $digits)", expect, PyMath.roundTo(value, digits), 0.0)
        }
    }

    @Test
    fun `roundHalfEven matches python round with zero digits`() {
        // 桌面期望：round(0.5)=0 / round(1.5)=2 / round(2.5)=2 / round(14.5)=14 / round(13.5)=14
        assertEquals(0L, PyMath.roundHalfEven(0.5))
        assertEquals(2L, PyMath.roundHalfEven(1.5))
        assertEquals(2L, PyMath.roundHalfEven(2.5))
        assertEquals(14L, PyMath.roundHalfEven(14.5))
        assertEquals(14L, PyMath.roundHalfEven(13.5))
        assertEquals(2L, PyMath.roundHalfEven(2.0))
    }

    @Test
    fun `median matches statistics median including even length`() {
        MEDIAN_VECTORS.forEach { (values, expect) ->
            assertEquals("median($values)", expect, PyMath.median(values), 0.0)
        }
    }

    @Test
    fun `date differences match python date subtraction including leap years`() {
        DATE_VECTORS.forEach { (a, b, days) ->
            assertEquals("$a - $b", days, PyMath.daysBetween(b, a))
        }
        // 契约 §2.1：period_days = (end - start).days + 1（同日起算算 1 天）
        assertEquals(1L, PyMath.daysBetween(LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 2)) + 1)
        assertEquals(8L, PyMath.daysBetween(LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 9)) + 1)
    }

    @Test
    fun `sqlite timestamp shape is utc second precision`() {
        val now = PyMath.nowSqliteUtc()
        assertTrue("格式应为 YYYY-MM-DD HH:MM:SS，实测=$now", SQLITE_TS.matches(now))
        assertTrue(SQLITE_TS.matches(PyMath.utcPlusDays(30)))
    }

    @Test
    fun `iso date parsing is lenient about time suffix and strict about garbage`() {
        assertEquals(LocalDate.of(2026, 9, 2), PyMath.parseIsoDate("2026-09-02"))
        assertEquals(LocalDate.of(2026, 9, 2), PyMath.parseIsoDate("2026-09-02T00:00:00"))
        assertNull(PyMath.parseIsoDate("2026-13-01"))
        assertNull(PyMath.parseIsoDate(""))
        assertNull(PyMath.parseIsoDate(null))
        assertEquals("2026-09-02", LocalDate.of(2026, 9, 2).toIso())
    }

    /** crud.sanitize_cycle_length：DDL chk_length 的同一区间。 */
    @Test
    fun `cycle length sanitization matches ddl check constraint`() {
        assertNull(PiqCrud.sanitizeCycleLength(6L))
        assertEquals(7L, PiqCrud.sanitizeCycleLength(7L))
        assertEquals(90L, PiqCrud.sanitizeCycleLength(90L))
        assertNull(PiqCrud.sanitizeCycleLength(91L))
        assertNull(PiqCrud.sanitizeCycleLength(null))
    }

    /**
     * 规范序（`predicted_date → created_at → id`，契约 §2 与 `crud.get_predictions_chronological`）。
     *
     * 断言的是**生产拼出来的那条 SQL**（[PiqCrud.predictionsChronologicalSql]），不是在测试里
     * 自建一份 lambda + 比较器：后者在生产实现已删（R9 的 Q-09）之后只剩自我安慰，
     * R9.3 的 L3-d 实测把规范序改坏 ⇒ 136 条全绿，那条用例名承诺的产品性质没有任何生产符号参与。
     */
    @Test
    fun `prediction order key anchors on predicted_date not created_at`() {
        val sql = PiqCrud.predictionsChronologicalSql(null)
        val orderBy = sql.substringAfter("ORDER BY ", "<SQL 里没有 ORDER BY>")
        assertEquals("规范序的排序键与次序", "predicted_date ASC, created_at ASC, id ASC", orderBy)
        // 截断形态只能追加 LIMIT，不得改动排序键
        assertEquals("带 limit 的取数必须沿用同一条规范序", sql,
            PiqCrud.predictionsChronologicalSql(50).substringBefore(" LIMIT "))
        assertEquals("limit 必须真的落到 SQL 上", "50",
            PiqCrud.predictionsChronologicalSql(50).substringAfter("LIMIT "))
    }

    companion object {
        private val SQLITE_TS = Regex("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}$")

        private val ROUND_VECTORS = listOf(
            Triple(2.675, 2, 2.67),
            Triple(0.5, 0, 0.0),
            Triple(1.5, 0, 2.0),
            Triple(2.5, 0, 2.0),
            Triple(-0.5, 0, 0.0),
            Triple(0.125, 2, 0.12),
            Triple(0.375, 2, 0.38),
            Triple(31.668996622012557, 4, 31.669),
            Triple(31.668996622012557, 2, 31.67),
            Triple(2.0, 0, 2.0),
            Triple(1.005675672006492, 4, 1.0057),
            Triple(0.8571428571428571, 4, 0.8571),
            Triple(14.5, 0, 14.0),
            Triple(13.5, 0, 14.0),
            Triple(1.0000000000000002e16, 0, 1.0000000000000002e16),
            Triple(31.745, 2, 31.75),
        )

        private val MEDIAN_VECTORS = listOf(
            Pair(listOf(28.0), 28.0),
            Pair(listOf(28.0, 30.0), 29.0),
            Pair(listOf(31.0, 28.0, 30.0), 30.0),
            Pair(listOf(30.0, 28.0, 31.0, 29.0), 29.5),
            Pair(listOf(7.0, 90.0), 48.5),
            Pair(listOf(1.0, 2.0, 3.0, 4.0, 5.0), 3.0),
            Pair(listOf(8.0, 8.0, 8.0), 8.0),
            Pair(listOf(2.0, 3.0), 2.5),
        )

        private val DATE_VECTORS = listOf(
            Triple(LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 9), -7L),
            Triple(LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 2), 0L),
            Triple(LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 1), -1L),
            Triple(LocalDate.of(2024, 2, 28), LocalDate.of(2024, 3, 1), -2L),
            Triple(LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 2), -2L),
        )
    }
}
