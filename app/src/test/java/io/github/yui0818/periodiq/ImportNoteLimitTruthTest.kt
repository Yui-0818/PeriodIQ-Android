package io.github.yui0818.periodiq

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导入备注超长文案的**单一真值**哨兵（R10.2 的 C-LIT —— R10.1 结构复检里唯一那条 FAIL）。
 *
 * 判据定义：备注上限的唯一真值是 [PiqConfig.MAX_NOTE_CHARS] ⇒ 用户可见文案里的数字必须由它插值而来，
 * 否则改了常量这句话就对不上真实闸门（`PiqRouter.importCycleRows` 判的是常量，文案说的是 2000）。
 *
 * 三条各钉一种回归，**判红能力不同**（R10.2 报告 §4 三段实测）：
 *  1. [the sentence exists once and keeps the limit interpolated] —— 扫 `src/main/java`：
 *     `的备注超长` 只允许出现一处，且那一行的**字符串字面量里不得有裸数字**、行内**必须引用常量**；
 *     把字面量 `2000` 写回去 ⇒ 不改常量就红。
 *  2. [the sentence carries the constant value] —— 直接调文案函数，期望值由常量插值。
 *     ⚠ 本条在常量当前为 2000 时**单独看是恒等的**（字面量回归也能过），所以它必须与第 1 条配对：
 *     它的判红要靠"把常量改成别的值"的变异实验（报告 §4 第三段之二）。
 *  3. [the limit is still what the contract's byte budget was computed from] —— 把常量钉在
 *     `docs/契约/跨端契约.md` §「导入体积上限」所依据的 2000 上：动常量必须同步契约，否则红。
 */
class ImportNoteLimitTruthTest {

    @Test
    fun `the sentence exists once and keeps the limit interpolated`() {
        val hits = mainSources.flatMap { (name, text) ->
            text.split('\n').mapIndexed { index, line -> Triple(name, index + 1, line) }
        }.filter { it.third.contains("的备注超长") }

        assertEquals(
            "`的备注超长` 在生产源码里只允许一处（两处=文案分叉，改常量只能改到一半），实测=${hits.map { "${it.first}:${it.second}" }}",
            1, hits.size,
        )
        val line = hits.first().third
        assertTrue("该行必须引用 PiqConfig.MAX_NOTE_CHARS，实测=$line", line.contains("PiqConfig.MAX_NOTE_CHARS"))
        val literals = Regex("\"[^\"]*\"").findAll(line).joinToString(separator = " ") { it.value }
        assertFalse(
            "字符串字面量里不得留下写死的上限，实测字面量=$literals（整行=$line）",
            Regex("\\d{2,}").containsMatchIn(literals),
        )
    }

    @Test
    fun `the sentence carries the constant value`() {
        assertEquals(
            "记录 2026-01-05 的备注超长（>${PiqConfig.MAX_NOTE_CHARS} 字符）",
            importNoteTooLongDetail("2026-01-05"),
        )
    }

    @Test
    fun `the limit is still what the contract's byte budget was computed from`() {
        // 契约 §「导入体积上限」的 6,834,114 B 预算是按 1000 周期 × 2000 字备注算的（`ImportBodyGateTest`
        // 也拿同一个数做边界）⇒ 常量一动，体积预算、`PiqConfig` 里的注释、本条都要跟着改；这条就是逼同步的那一下。
        assertEquals("备注上限被改了却没人重算导入体积预算（见 docs/契约/跨端契约.md 与 PiqConfig.kt 的注释）",
            2000, PiqConfig.MAX_NOTE_CHARS)
    }

    /**
     * 生产源码 = `src/main/java` 下全部 `.kt`。
     * 只扫 main 不扫 test：本文件的期望文案本身就带着 `2026-01-05` 这种数字，扫进来会自己判自己红。
     */
    private val mainSources: List<Pair<String, String>> by lazy {
        mainSourceDir().walkTopDown().filter { it.isFile && it.extension == "kt" }
            .map { it.name to it.readText() }
            .toList()
    }

    /** 单测的工作目录由构建工具决定（AGP 给模块目录；命令行从仓库根起也是模块目录），因此向上找。 */
    private fun mainSourceDir(): File {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            for (rel in listOf("src/main/java", "app/src/main/java")) {
                val candidate = File(dir, rel)
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw IllegalStateException("未找到生产源码目录，无法执行真值扫描（cwd=${System.getProperty("user.dir")}）")
    }
}
