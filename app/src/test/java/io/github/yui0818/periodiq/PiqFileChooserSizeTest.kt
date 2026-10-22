package io.github.yui0818.periodiq

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导入文件大小的宿主侧判定（R13.1 F5 / R12-P2-05）。
 *
 * 设备侧读取 `OpenableColumns.SIZE` 这一段靠 Android ContentResolver，JVM 不可驱动；
 * 这里钉住的是**判定函数**：任一已知大小超过 8 MiB 就拒绝，未知（null）不能误拒。
 *
 * 判红手段：把 `>` 改成 `>=` ⇒ `exactly at the limit` 红；把 null 当成超限 ⇒
 * `unknown size is not a rejection` 红。
 */
class PiqFileChooserSizeTest {

    private val limit = PiqConfig.MAX_IMPORT_BYTES

    @Test
    fun `a file over the limit is rejected`() {
        assertTrue(PiqFileChooser.anyOverLimit(listOf(limit + 1)))
    }

    @Test
    fun `exactly at the limit is allowed`() {
        assertFalse(PiqFileChooser.anyOverLimit(listOf(limit)))
    }

    @Test
    fun `unknown size is not a rejection`() {
        assertFalse(PiqFileChooser.anyOverLimit(listOf(null)))
    }

    @Test
    fun `one oversized file rejects the whole selection`() {
        assertTrue(PiqFileChooser.anyOverLimit(listOf(1024L, limit + 1, 2048L)))
        assertFalse(PiqFileChooser.anyOverLimit(listOf(1024L, limit, null)))
    }

    @Test
    fun `the production entry checks size before handing uris to the page`() {
        val body = ProductionSource.functionBody("PiqFileChooser.kt", "private fun deliver(")
        val checkAt = body.indexOf("anyOverLimit(known)")
        assertTrue("deliver 必须先走 anyOverLimit", checkAt >= 0)
        assertTrue("超限时不得把 uris 交给页面", checkAt < body.indexOf("callback?.onReceiveValue(uris)"))
    }

    @Test
    fun `the size limit reuses the shared config constant`() {
        val code = ProductionSource.commentsRemoved("PiqFileChooser.kt")
        assertFalse("不得重复定义导入上限", Regex("""const val MAX_IMPORT_BYTES""").containsMatchIn(code))
        assertTrue("必须引用 PiqConfig 的常量", code.contains("PiqConfig.MAX_IMPORT_BYTES"))
    }
}
