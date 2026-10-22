package io.github.yui0818.periodiq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L2E-04 的失败回执文案哨兵（R9.3）。
 *
 * 判据定义：`PiqExport.saveToDisk` 能返回的**每一个**失败 `code` 都必须有一句非空、
 * 互不相同、且明说"没有导出/没有落盘"的文案——因为页内不读桥的返回值，
 * 文案（Toast）就是用户唯一的失败通道；空文案 = 把失败重新咽回去。
 *
 * 这一段是**文案钉住**（改文案要让哨兵先红）；"失败到底有没有回执"这一行为判据
 * 由设备侧 logcat 的 `export|receipt|ok=false` 行给出（见报告 §4 的 L2E-04 四段）。
 */
class ExportFailureReceiptTest {

    private val codes = listOf("TOO_LARGE", "EMPTY_PAYLOAD", "BAD_BASE64", "WRITE_FAILED")

    @Test
    fun `every rejection code has a non-empty user facing text`() {
        for (code in codes) {
            val text = PiqExport.failureText(code, "backup.json")
            assertTrue("$code 的文案为空", text.isNotBlank())
            assertTrue("$code 的文案没有说明失败结果：$text",
                text.contains("没有导出") || text.contains("没有落盘"))
        }
    }

    @Test
    fun `rejection texts are distinguishable from each other`() {
        val texts = codes.map { PiqExport.failureText(it, "backup.json") }.toSet()
        assertEquals("四个 code 必须给四种不同文案", codes.size, texts.size)
    }

    @Test
    fun `unknown code still produces a text instead of an empty string`() {
        val text = PiqExport.failureText("SOMETHING_NEW", "backup.json")
        assertTrue("未知 code 必须兜底成可读文案：$text", text.isNotBlank())
        assertTrue("兜底必须把 code 与文件名都带出来（这条与上一条判据不同：把兜底改回复用已知文案就会红）",
            text.contains("SOMETHING_NEW") && text.contains("backup.json"))
    }

    @Test
    fun `over-limit text quotes the real byte limit not a hand-written number`() {
        val mb = PiqExport.MAX_EXPORT_BYTES / (1024 * 1024)
        assertTrue(PiqExport.failureText("TOO_LARGE", "x.json").contains(mb.toString()))
    }
}
