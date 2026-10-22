package io.github.yui0818.periodiq

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 桥与网关日志的 `bytes=` 列（R9.2 小项 #14）必须等于真正上线的 UTF-8 字节数。
 *
 * 改前只有一列 `bodyLen=${body.length}`，记的是 **UTF-16 字符数**：R9.1 的 oversize 夹具
 * 因此把 12 MB 量级的体报成三分之一。改后 `bytes=` 由 [utf8ByteLength] 逐码点算出；
 * 期望值不是我推的，是拿 JDK 自己的编码器 `toByteArray(Charsets.UTF_8).size` 当 oracle 逐值对照
 * —— 12 个种子的随机码点串（码点池含代理段，所以成对与落单两种形态都被覆盖）。
 *
 * ⚠ 落单的代理是 **1 字节**（JDK 21 实测：不可映射代理被替换成单个 `?`），不是 U+FFFD 的 3 字节；
 * 这条是先用 jshell 量出来再写进实现的。
 */
class Utf8ByteLengthTest {

    @Test
    fun `counts match the jdk encoder on the four length classes`() {
        val samples = listOf(
            "", "abc", "周", "周期abc", "éᎤ", "ހ",
            "\uD83D\uDE00", "a\uD83D\uDE00b周",
            "\uD800", "\uD800a", "\uD800\uD800", "\uDC00", "\uDC00周",
        )
        for (s in samples) {
            assertEquals("样本=${escape(s)}", s.toByteArray(Charsets.UTF_8).size, s.utf8ByteLength())
        }
    }

    /** 一/二/三字节的分界码点必须各归各类（越界一格就整串错位）。 */
    @Test
    fun `length class boundaries are one below each cutoff`() {
        listOf('\u0000', '\u007F', '\u0080', '\u07FF', '\u0800', '\uFFFD').forEach { c ->
            val s = "x$c"
            assertEquals("码点=${c.code}", s.toByteArray(Charsets.UTF_8).size, s.utf8ByteLength())
        }
    }

    /**
     * 12 个种子的随机码点串逐个与编码器对照（`AGENTS.md` §3.3：涉及随机的结论 ≥8 种子）。
     * 每个种子 240 个码点，池子按六个区取，代理区单独占两格 ⇒ 落单与成对都会自然出现。
     */
    @Test
    fun `counts match the encoder on random code unit strings across twelve seeds`() {
        for (seed in 0L until 12) {
            val random = Random(seed)
            val s = buildString(240) { repeat(240) { append(pickUnit(random)) } }
            assertEquals(
                "seed=$seed chars=${s.length}",
                s.toByteArray(Charsets.UTF_8).size,
                s.utf8ByteLength(),
            )
        }
    }

    private fun pickUnit(random: Random): Char = when (random.nextInt(6)) {
        0 -> 'a'.code + random.nextInt(26)
        1 -> 0x80 + random.nextInt(0x780)
        2 -> 0x800 + random.nextInt(0xF800)
        3 -> 0x4E00 + random.nextInt(0x1000)
        4 -> 0xD800 + random.nextInt(0x400)
        else -> 0xDC00 + random.nextInt(0x400)
    }.toChar()

    /** 把样本里的代理写成可见转义，失败时读数可复述（否则报告里是一串问号）。 */
    private fun escape(value: String): String = buildString {
        value.forEach { c ->
            if (c.code < 0x20 || c.code in 0xD800..0xDFFF) append("\\u%04X".format(c.code)) else append(c)
        }
    }
}
