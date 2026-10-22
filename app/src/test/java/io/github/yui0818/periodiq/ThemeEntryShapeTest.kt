package io.github.yui0818.periodiq

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L2D-F3 哨兵：主题清单里的 `entry` 必须是一个**安全的单段文件名**。
 *
 * 为什么形态要在这里判（而不是只在拼 URL 时判）：入口名同时被用在文件系统与 URL 两个去处，
 * `#` / `?` 在前者是合法字符（文件真的存在 ⇒ 探测放行），在后者是"片段/查询"的起点
 * （被截掉 ⇒ `theme/<截断后的路径>` 404，用户看到空白页）。
 * 判据不同一才是这条缺陷的内容，所以闸门只有一处：`ThemeResolver.probe()` 选根之前。
 *
 * 三条畸形形态与 `docs/契约/BDD场景清单.md` 里那条设备场景逐字一致（`a#b.html` / `a?c.html` /
 * 对照 `index.html`）；这里断言的是**生产符号**，不另建一份规则（R9.4 审计 §3 第 2 条的教训）。
 */
class ThemeEntryShapeTest {

    @Test
    fun `plain file names are accepted`() {
        listOf("index.html", "home.htm", "a-b_1.html", "Page2.HTML", "1.html").forEach {
            assertTrue("合法入口名不该被拦：$it", ThemeResolver.isSingleSafeEntry(it))
        }
    }

    @Test
    fun `fragment and query forms are rejected - the url would point elsewhere`() {
        listOf("a#b.html", "a?c.html", "index.html#x", "index.html?v=1").forEach {
            assertFalse("文件系统能过但 URL 会被截断：$it", ThemeResolver.isSingleSafeEntry(it))
        }
    }

    @Test
    fun `path forms are rejected - they would leave the theme root`() {
        listOf("/etc/passwd", "../evil.html", "sub/dir.html", "a\\b.html", "%2e%2e/x.html").forEach {
            assertFalse("越根形态：$it", ThemeResolver.isSingleSafeEntry(it))
        }
    }

    @Test
    fun `blank hidden and oversized names are rejected`() {
        listOf("", " ", ".", "..", ".hidden.html", "x".repeat(128) + ".html").forEach {
            assertFalse("空名 / 纯点号 / 隐藏文件 / 超长：len=${it.length}", ThemeResolver.isSingleSafeEntry(it))
        }
    }

    @Test
    fun `percent is rejected because the loader decodes it`() {
        // `a%23b.html` 在文件系统侧是这个名字，在 URL 侧会被解成 `a#b.html` —— 两个去处分叉
        assertFalse(ThemeResolver.isSingleSafeEntry("a%23b.html"))
    }
}
