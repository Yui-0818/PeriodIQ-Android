package io.github.yui0818.periodiq

import io.github.yui0818.periodiq.domain.import.ImportGate

import java.time.LocalDate
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** R13.2 B1：导入门禁 G1–G5 的固定混合夹具特征测试（搬家前先绿，搬家后仍绿）。 */
class ImportGateFixtureTest {

    private val today = LocalDate.of(2026, 6, 1)
    private val history = listOf(28.0, 30.0)

    /**
     * 输入序 = C, E, B, A, D, F；日历序 = A, B, C, D, E, F。
     * C 与 D 不带 cycle_length，必须按日历前驱推导（C=31、D=70），不能按列表下标。
     */
    @Test
    fun `mixed fixture keeps G1 to G5 order fields values and messages`() {
        val cycles = listOf(
            cycle("2026-04-01", "2026-04-12"),          // C: G5 12
            cycle("2026-06-15", "2026-06-16", 29),      // E: G2 start + end
            cycle("2026-03-01", "2026-03-05", 131),     // B: G1 + G3
            cycle("2026-01-01", "2026-01-03"),          // A: G5 3
            cycle("2026-06-10", null),                  // D: G2 start + G3 70
            cycle("2026-10-01", "2026-10-05", 29),      // F: G2 start + end + G4
        )
        val expected = listOf(
            anomaly("G5", "period_days", 12.0, "2–10 天",
                "记录 2026-04-01 的经期时长 12 天超出常见范围（2–10 天）"),
            anomaly("G2", "period_start", "2026-06-15", "不晚于今天",
                "记录 2026-06-15 的开始日期 2026-06-15 晚于今天（2026-06-01）"),
            anomaly("G2", "period_end", "2026-06-16", "不晚于今天",
                "记录 2026-06-15 的结束日期 2026-06-16 晚于今天（2026-06-01）"),
            anomaly("G1", "cycle_length", 131.0, "7–90 天",
                "记录 2026-03-01 的周期长度 131 天超出常见范围（7–90 天）"),
            anomaly("G3", "cycle_length", 131.0, "偏离历史中位数不超过 14 天",
                "记录 2026-03-01 的周期长度 131 天与库内历史中位数 29.0 天偏离 102.0 天（阈值 14 天）"),
            anomaly("G2", "period_start", "2026-06-10", "不晚于今天",
                "记录 2026-06-10 的开始日期 2026-06-10 晚于今天（2026-06-01）"),
            anomaly("G3", "cycle_length", 70.0, "偏离历史中位数不超过 14 天",
                "记录 2026-06-10 的周期长度 70 天与库内历史中位数 29.0 天偏离 41.0 天（阈值 14 天）"),
            anomaly("G2", "period_start", "2026-10-01", "不晚于今天",
                "记录 2026-10-01 的开始日期 2026-10-01 晚于今天（2026-06-01）"),
            anomaly("G2", "period_end", "2026-10-05", "不晚于今天",
                "记录 2026-10-01 的结束日期 2026-10-05 晚于今天（2026-06-01）"),
            anomaly("G4", "period_start_gap", 108.0, "相邻开始日间隔不超过 90 天",
                "记录 2026-10-01 与上一条开始日 2026-06-15 间隔 108 天（阈值 90 天）"),
        )

        val actualArray = ImportGate.evaluate(cycles, history, today)
        val actual = (0 until actualArray.length()).map { i ->
            val row = actualArray.getJSONObject(i)
            anomaly(
                row.getString("code"),
                row.getString("field"),
                normalize(row.get("value")),
                row.getString("expected"),
                row.getString("message"),
            )
        }

        assertEquals(
            "异常条数，实测=" + actual.joinToString(" || ") { "${it.code}/${it.field}/${it.value}/${it.message}" },
            expected.size, actual.size,
        )
        assertEquals(expected, actual)
        assertEquals(setOf("G1", "G2", "G3", "G4", "G5"), actual.map { it.code }.toSet())
        assertTrue("输出必须按输入序（C,E,B,A,D,F），实测=${actual.map { it.message }}", actual.first().message.contains("2026-04-01"))
    }

    @Test
    fun `empty history disables G3 and G4 but keeps G1 G2 G5`() {
        val cycles = listOf(cycle("2026-06-10", "2026-06-20", 131))
        val actual = ImportGate.evaluate(cycles, emptyList(), today)
        val codes = (0 until actual.length()).map { actual.getJSONObject(it).getString("code") }
        assertEquals(listOf("G1", "G2", "G2", "G5"), codes)
    }

    private fun cycle(start: String, end: String?, length: Number? = null, days: Number? = null): JSONObject =
        JSONObject().apply {
            put("period_start", start)
            if (end != null) put("period_end", end)
            if (length != null) put("cycle_length", length)
            if (days != null) put("period_days", days)
        }

    private fun anomaly(code: String, field: String, value: Any?, expected: String, message: String) =
        Anomaly(code, field, value, expected, message)

    private fun normalize(value: Any?): Any? = if (value is Number) value.toDouble() else value

    private data class Anomaly(
        val code: String,
        val field: String,
        val value: Any?,
        val expected: String,
        val message: String,
    )
}
