package io.github.yui0818.periodiq

import io.github.yui0818.periodiq.domain.import.ImportGate

import java.util.Locale
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 门禁异常**文案里的数字**与桌面逐值一致（R9.1 的 L2-A finding F-2 / F-3，用户可见 P2）。
 *
 * 桌面用 `f"{cycle_length:.0f}"` 与 `f"{median:.1f}"`（`app/services/gate.py:153/175/176/202`）；
 * 安卓改前用 `${x.toInt()}`（向零截断）与 `"...%.1f".format(...)`（跟随 `Locale.getDefault()`）。
 * 期望值不是我推的，是**主机 Python 3.11 现跑** `f-string` 得到的：
 * `6.7→'7'`、`6.5→'6'`、`7.5→'8'`、`100.5→'100'`、`22.34→'22'`、`0.5→'0'`、`22.35→'22.4'`。
 */
class GateMessageFormatTest {

    private val today = LocalDate.of(2026, 10, 9)

    @Test
    fun `zero-decimal day counts follow python banker rounding not truncation`() {
        mapOf(
            6.7 to "7", 6.5 to "6", 7.5 to "8", 100.5 to "100", 22.34 to "22", 0.5 to "0",
        ).forEach { (value, desktop) ->
            assertEquals("桌面 f{$value:.0f} 的答案", desktop, ImportGate.pyDays(value))
        }
    }

    @Test
    fun `one-decimal counts print with a dot even when the device locale uses a comma`() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("改前这种形态在 de_DE 下会印成 22,3", "22.3", ImportGate.pyDays1(22.34))
            assertEquals("桌面 f{22.35:.1f}", "22.4", ImportGate.pyDays1(22.35))
        } finally {
            Locale.setDefault(saved)
        }
        assertEquals("恢复默认 locale 后仍同一形态", "22.3", ImportGate.pyDays1(22.34))
    }

    @Test
    fun `g1 message quotes the desktop rounded digit`() {
        val report = ImportGate.evaluate(
            listOf(JSONObject().put("period_start", "2026-09-01").put("cycle_length", 6.7)),
            emptyList(), today,
        )
        val messages = (0 until report.length()).map { report.getJSONObject(it).getString("message") }
        assertTrue("G1 文案应印 7 天（桌面口径），实测=$messages",
            messages.any { it.contains("周期长度 7 天超出常见范围") })
    }

    @Test
    fun `g5 message quotes the desktop rounded digit`() {
        val report = ImportGate.evaluate(
            listOf(JSONObject().put("period_start", "2026-09-01").put("period_days", 10.7)),
            emptyList(), today,
        )
        val messages = (0 until report.length()).map { report.getJSONObject(it).getString("message") }
        assertTrue("G5 文案应印 11 天（桌面 f{10.7:.0f}=11），实测=$messages",
            messages.any { it.contains("经期时长 11 天超出常见范围") })
    }
}
