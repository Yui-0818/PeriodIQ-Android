package io.github.yui0818.periodiq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导出 base64 的前置上限（R13.1 F4 / R12-P2-04）。
 *
 * 判据：超过 `maxBytes` 的载荷必须返回 `TooLarge`，且解码函数**一次都不执行**；
 * 合法载荷仍返回原字节；坏 base64 才返回 `BadBase64`。
 *
 * 判红手段：把 `decodeWithinLimit` 的上限判断移到 `decode` 之后，
 * `oversized payload is rejected before decode` 的 `decodeCalls` 会变成 1。
 */
class ExportLimitTest {

    private val bigBase64 = "A".repeat(16) // 解码后 12 字节（16 个字符、无 padding）

    @Test
    fun `oversized payload is rejected before decode`() {
        var decodeCalls = 0
        val outcome = PiqExport.decodeWithinLimit(bigBase64, maxBytes = 8L) {
            decodeCalls += 1
            ByteArray(12)
        }

        assertTrue(outcome is PiqExport.LimitedPayload.TooLarge)
        assertEquals("超限载荷不得调用解码器", 0, decodeCalls)
        assertEquals(12L, (outcome as PiqExport.LimitedPayload.TooLarge).estimated)
    }

    @Test
    fun `a payload at the limit is decoded normally`() {
        var decodeCalls = 0
        val outcome = PiqExport.decodeWithinLimit(bigBase64, maxBytes = 12L) {
            decodeCalls += 1
            ByteArray(12)
        }

        assertTrue(outcome is PiqExport.LimitedPayload.Bytes)
        assertEquals(1, decodeCalls)
        assertEquals(12, (outcome as PiqExport.LimitedPayload.Bytes).bytes.size)
    }

    @Test
    fun `bad base64 is reported after a bounded estimate`() {
        var decodeCalls = 0
        val outcome = PiqExport.decodeWithinLimit(bigBase64, maxBytes = 1024L) {
            decodeCalls += 1
            null
        }

        assertEquals(PiqExport.LimitedPayload.BadBase64, outcome)
        assertEquals(1, decodeCalls)
    }

    @Test
    fun `whitespace and padding are accounted for`() {
        assertEquals(0L, PiqExport.estimatedDecodedBytes(""))
        assertEquals(1L, PiqExport.estimatedDecodedBytes("AA=="))
        assertEquals(12L, PiqExport.estimatedDecodedBytes("AAAAAAAAAAAAAAAA"))
        assertEquals(12L, PiqExport.estimatedDecodedBytes("AAAA\nAAAA\r\nAAAA\tAAAA"))
    }

    @Test
    fun `an empty payload is not decoded`() {
        var decodeCalls = 0
        val outcome = PiqExport.decodeWithinLimit("", maxBytes = 64L) {
            decodeCalls += 1
            ByteArray(0)
        }

        assertEquals(PiqExport.LimitedPayload.Empty, outcome)
        assertEquals(0, decodeCalls)
        assertFalse(outcome is PiqExport.LimitedPayload.Bytes)
    }

    @Test
    fun `excess padding is rejected before decode`() {
        var decodeCalls = 0
        val outcome = PiqExport.decodeWithinLimit("A".repeat(128) + "=".repeat(64), maxBytes = 1024L) {
            decodeCalls += 1
            ByteArray(0)
        }

        assertEquals("过量 '=' 是结构非法输入", PiqExport.LimitedPayload.BadBase64, outcome)
        assertEquals("结构非法输入不得进入解码器", 0, decodeCalls)
    }

    @Test
    fun `padding before the end is rejected before decode`() {
        var decodeCalls = 0
        val outcome = PiqExport.decodeWithinLimit("AA=A", maxBytes = 1024L) {
            decodeCalls += 1
            ByteArray(1)
        }

        assertEquals(PiqExport.LimitedPayload.BadBase64, outcome)
        assertEquals(0, decodeCalls)
    }

    @Test
    fun `the production entry routes through the bounded decoder`() {
        val body = ProductionSource.functionBody("PiqExport.kt", "fun saveToDisk(")
        assertTrue("saveToDisk 必须先走 decodeWithinLimit", body.contains("decodeWithinLimit(payload, MAX_EXPORT_BYTES)"))
        assertTrue(
            "前置上限必须在写盘之前",
            body.indexOf("decodeWithinLimit(") < body.indexOf("target.outputStream()"),
        )
    }
}
