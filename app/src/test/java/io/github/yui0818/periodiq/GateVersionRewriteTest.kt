package io.github.yui0818.periodiq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 门控版本串重写的接线哨兵（R10.1-VER，用户可见：页内「关于」显示的是桌面前端版本）。
 *
 * 判据定义（三条各自会判红）：
 *  1. **接线**：`documentStartScript(seed, version)` 产出的脚本里必须同时出现
 *     目标串 `版本：v<version>`、可分辨读数 `version-rewrite` 与路由触发点 `hashchange`
 *     —— 撤掉接线（= 改前形态）必须报红；
 *  2. **退化态**：`version` 为 null 或空串时**整段省略**，脚本里不许出现 `版本：v`
 *     （不给页面塞一个空版本号）；
 *  3. **转义**：版本串含双引号时必须以 `JSONObject.quote` 的形态出现（与 `tokenSeed` 同一口径），
 *     不许把裸引号拼进 JS 字面量。
 *
 * ⚠ 本类只钉"脚本内容与接线"。**页内真的把那一行改掉没有**属引擎判据，
 * 见 R10.1 报告 §3（Chromium 夹具三态）与 §5（WebView 装机判据 = 未覆盖）。
 */
class GateVersionRewriteTest {

    @Test
    fun `document start script wires the android version when supplied`() {
        val script = AndroidGate.documentStartScript(null, "9.9.9-mut")
        assertTrue("缺目标串", script.contains("版本：v9.9.9-mut"))
        assertTrue("缺可分辨读数", script.contains("version-rewrite"))
        assertTrue("缺路由触发点", script.contains("hashchange"))
    }

    @Test
    fun `document start script omits the rewrite entirely when version is absent`() {
        listOf(null, "").forEach { v ->
            val script = AndroidGate.documentStartScript("seed-token", v)
            assertFalse("退化态不该出现版本串", script.contains("版本：v"))
            assertFalse("退化态不该出现重写读数", script.contains("version-rewrite"))
            assertTrue("其余门控段必须在场", script.contains(AndroidGate.CLASS_FLAG))
        }
    }

    @Test
    fun `version string is quoted so a quote cannot break out of the literal`() {
        val script = AndroidGate.versionRewrite("a\"b")
        // quote 的产物是带首尾引号、内部引号被反斜杠转义的一整串；前缀 `版本：v` 也在同一串里。
        assertTrue("必须出现 quote 后的形态", script.contains("WANT = \"版本：va\\\"b\""))
        assertFalse("不许出现未转义而断串的字面量", script.contains("WANT = \"版本：va\"b\""))
    }

    /** `MAX_NAME_CHARS` 与快照实际导出名之间的边界：消毒不得把扩展名吃掉（既有行为的复核）。 */
    @Test
    fun `desktop-shaped export names survive sanitizing with their extension`() {
        assertEquals("PeriodIQ-__-20261018.json", PiqExport.sanitizeName("PeriodIQ-备份-20261018.json"))
        assertEquals("PeriodIQ-__-20261018.csv", PiqExport.sanitizeName("PeriodIQ-周期-20261018.csv"))
    }
}
