package io.github.yui0818.periodiq

import io.github.yui0818.periodiq.domain.import.ImportGate

import java.time.LocalDate
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导入异常门禁 G1-G5 哨兵（R3 F3；桌面唯一实现 `app/services/gate.py::evaluate_gate` 的同口径移植）。
 *
 * 四道闸门里的**阴性对照**由"同一份数据在无历史 / 有历史两种库况下结果不同"承担，
 * **退化态**由空列表、只有开始日、区间倒挂不填长度等形态覆盖（`AGENTS.md` §3.4 第 4 条）。
 */
class ImportGateTest {

    private val today = LocalDate.of(2026, 10, 9)

    @Test
    fun `normal import raises nothing`() {
        val cycles = listOf(cycle("2026-09-01", "2026-09-06"), cycle("2026-09-29", "2026-10-04"))
        assertEquals(0, ImportGate.evaluate(cycles, listOf(28.0, 30.0), today).length())
    }

    @Test
    fun `g1 flags cycle length out of 7 to 90`() {
        val codes = codes(listOf(cycle("2026-09-01"), cycle("2026-09-09", null, cycleLength = 120)), emptyList())
        assertTrue("应命中 G1，实测=$codes", codes.contains("G1"))
    }

    @Test
    fun `g2 flags dates later than today`() {
        val codes = codes(listOf(cycle("2026-12-01")), emptyList())
        assertTrue("未来日期必须命中 G2，实测=$codes", codes.contains("G2"))
    }

    @Test
    fun `g3 and g4 do not apply without history but apply with it`() {
        // 同一份数据：库内无历史 ⇒ 不报 G3/G4；有历史 ⇒ 偏离中位数 30 的 60 天必须报 G3
        val outlier = listOf(cycle("2026-08-01"), cycle("2026-09-30"))
        val withoutHistory = codes(outlier, emptyList())
        assertEquals("无历史时 G3/G4 不适用，实测=$withoutHistory", emptyList<String>(),
            withoutHistory.filter { it == "G3" || it == "G4" })
        val withHistory = codes(outlier, listOf(28.0, 30.0, 29.0))
        assertTrue("有历史时偏离中位数必须报 G3，实测=$withHistory", withHistory.contains("G3"))
    }

    @Test
    fun `g5 flags period days outside 2 to 10`() {
        assertTrue(codes(listOf(cycle("2026-09-01", "2026-09-01")), emptyList()).contains("G5"))
        assertTrue(codes(listOf(cycle("2026-09-01", "2026-09-20")), emptyList()).contains("G5"))
    }

    /**
     * 关键不变量（桌面 R2 审计同一条）：**导出接口给的是倒序**，
     * 门禁必须按日历升序派生周期长度与相邻间隔。
     *
     * 这条同时是"按列表下标做差"那种朴素写法的阴性对照：
     * 倒序输入若按下标做差会得到 `-28` ⇒ 命中 G1；按日历序则恒得 `28` ⇒ 无异常。
     */
    @Test
    fun `derived lengths use chronological order not list order`() {
        val ascending = listOf(
            cycle("2026-08-01"), cycle("2026-08-29"), cycle("2026-09-26"),
        )
        val descending = ascending.reversed()
        val history = listOf(28.0, 30.0)
        assertEquals("升序：无异常", 0, ImportGate.evaluate(ascending, history, today).length())
        assertEquals("倒序（导出接口的真实形态）：同样不得有异常", 0, ImportGate.evaluate(descending, history, today).length())
    }

    @Test
    fun `anomaly payload carries code field value expected message`() {
        val report = ImportGate.evaluate(listOf(cycle("2026-12-01")), emptyList(), today)
        assertTrue(report.length() > 0)
        val first = report.getJSONObject(0)
        assertEquals("G2", first.getString("code"))
        assertEquals("period_start", first.getString("field"))
        assertEquals("2026-12-01", first.getString("value"))
        assertEquals("不晚于今天", first.getString("expected"))
        assertTrue(first.getString("message").contains("晚于今天"))
    }

    private fun codes(cycles: List<JSONObject>, history: List<Double>): List<String> {
        val report = ImportGate.evaluate(cycles, history, today)
        return (0 until report.length()).map { report.getJSONObject(it).getString("code") }
    }

    private fun cycle(start: String, end: String? = null, cycleLength: Long? = null): JSONObject =
        JSONObject().put("period_start", start)
            .put("period_end", end ?: JSONObject.NULL)
            .let { json -> if (cycleLength == null) json else json.put("cycle_length", cycleLength) }
}
