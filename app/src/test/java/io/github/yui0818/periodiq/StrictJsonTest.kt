package io.github.yui0818.periodiq

import io.github.yui0818.periodiq.domain.import.ImportBodyGate
import io.github.yui0818.periodiq.domain.import.StrictJson

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `StrictJson` 的接受集哨兵（R9 的 REG-03）。
 *
 * 期望集**不是推断**：桌面 Python `json.loads`（3.11.15）逐形态实测（读数见 R9 报告 §3）——
 * 桌面拒的这里必须拒（两端同答案），
 * 桌面**放行**的 `NaN`/`Infinity`/`-Infinity` 这里也放行（比桌面更严同样是分叉）。
 *
 * 四道闸门对照（`AGENTS.md` §3.4）：
 *  · 阳性对照 = 已知合法文档必须返回 -1；
 *  · 阴性对照 = 已知非法形态必须返回非负下标（同一套断言、两种结果，才说明判据连得上）；
 *  · 稳定化 = 纯函数，无时序；
 *  · 判据显式化 = 退化态（空串、纯空白、只有 `{`）都列在拒绝集里。
 */
class StrictJsonTest {

    private val accepted = listOf(
        "{}",
        "[]",
        "{\"a\":1}",
        "  {\"a\":  [1, 2.5, -3e2, true, false, null]}  ",
        "\"纯字符串\"",
        "5",
        "-0",
        "0.5",
        "{\"a\":\"\\u00e9\\n\\\"\"}",
        "{\"a\": NaN}",
        "{\"a\": Infinity}",
        "{\"a\": -Infinity}",
        "{\"emoji\":\"😀\"}",
    )

    private val rejected = listOf(
        "尾随垃圾" to "{} trailing",
        "尾随逗号" to "{\"a\":1,}",
        "单引号字符串" to "{'a': 1}",
        "行注释" to "// c\n{\"a\": 1}",
        "块注释" to "{\"a\": 1 /* x */}",
        "十六进制" to "{\"a\": 0x1f}",
        "前导零" to "{\"a\": 01}",
        "未加引号的键" to "{a: 1}",
        "缺整数部分" to "{\"a\": .5}",
        "尾随小数点" to "{\"a\": 1.}",
        "加号开头" to "{\"a\": +1}",
        "空文档" to "",
        "纯空白" to "   \t\n",
        "只有左括号" to "{",
        "缺右括号" to "{\"a\":1",
        "字符串未闭合" to "{\"a\": \"x}",
        "字符串里有裸换行" to "{\"a\": \"x\ny\"}",
        "非法转义" to "{\"a\": \"\\x41\"}",
        "NaN 拼错" to "{\"a\": Nan}",
        "true 后面多个字符" to "truex",
    )

    @Test
    fun `positive control - every desktop-accepted form parses`() {
        for (form in accepted) {
            assertEquals("合法形态被判非法：'$form'", -1, StrictJson.firstSyntaxError(form))
        }
    }

    @Test
    fun `negative control - every desktop-rejected form is rejected with an offset`() {
        for ((name, form) in rejected) {
            val at = StrictJson.firstSyntaxError(form)
            assertTrue("[$name] 必须非法，实测=$at：'$form'", at >= 0)
        }
    }

    @Test
    fun `the reported offset points at the first offending character`() {
        // `{"a":1,}` ⇒ 逗号之后期望"键或}"，读到 `}` 之前那一步失败的位置是下标 7
        assertEquals(7, StrictJson.firstSyntaxError("{\"a\":1,}"))
        assertEquals(0, StrictJson.firstSyntaxError(""))
    }

    @Test
    fun `top level kind naming feeds the tiered message`() {
        assertEquals("对象", StrictJson.topLevelKind("{}"))
        assertEquals("数组", StrictJson.topLevelKind("[]"))
        assertEquals("字符串", StrictJson.topLevelKind("\"x\""))
        assertEquals("数字或 NaN/Infinity", StrictJson.topLevelKind("5"))
        assertEquals("字面量 null", StrictJson.topLevelKind("null"))
    }

    @Test
    fun `bom is not a syntax decision here - the gate strips it before calling`() {
        // 这条把"BOM 谁负责"钉住：StrictJson 不认 BOM（桌面也不认），
        // 剥 BOM 是 ImportBodyGate 的职责 ⇒ 两边各自的口径都能单独回归。
        val withBom = "\uFEFF" + "{}"
        assertTrue(StrictJson.firstSyntaxError(withBom) >= 0)
    }
}
