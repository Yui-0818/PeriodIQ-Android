package io.github.yui0818.periodiq

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `reminder-*` 日志的 PII 守卫（R13.1 F9 / R12-P2-09）。
 *
 * 生产日志不得出现预测/触发日期：禁止字段名 `triggerDate=`、`predictedStart=`、
 * `|start=`，也不得在日志行里出现 `YYYY-MM-DD` 字面量。改回旧字段（例如
 * `triggerDate=${alarm.triggerDate}`）⇒ 第一条断言立刻红。
 *
 * 这不是行为哨兵（JVM 没有 Android Log），但设备侧 `logcat` 扫描会在报告 §3 复核；
 * 两者一起覆盖“源码里没有 + 运行时没有”。
 */
class ReminderLogPrivacyTest {

    private val sources: String by lazy {
        listOf("PiqReminderScheduler.kt", "PiqReminderStore.kt", "PiqReminderReceiver.kt")
            .joinToString("\n") { sourceOf(it) }
    }

    @Test
    fun `reminder logs do not carry date fields`() {
        listOf("triggerDate=", "predictedStart=", "|start=").forEach { forbidden ->
            assertFalse("reminder 日志不得含日期字段 `$forbidden`", sources.contains(forbidden))
        }
    }

    @Test
    fun `reminder log lines contain no ISO date literal`() {
        val isoDate = Regex("""\d{4}-\d{2}-\d{2}""")
        sources.lineSequence()
            .filter { it.contains("reminder-") }
            .forEach { line ->
                assertFalse("reminder 日志行出现 ISO 日期：$line", isoDate.containsMatchIn(line))
            }
    }

    @Test
    fun `non PII summary fields are still present`() {
        assertTrue("重排日志应保留 hasPrediction", sources.contains("hasPrediction="))
        assertTrue("重排日志应保留 armed", sources.contains("armed="))
        assertTrue("重排日志应保留 days", sources.contains("days="))
    }

    private fun sourceOf(fileName: String): String =
        mainSourceDir().walkTopDown()
            .firstOrNull { it.isFile && it.name == fileName }
            ?.readText()
            ?: throw IllegalStateException("生产源码里找不到 $fileName，源守卫已失效")

    private fun mainSourceDir(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            for (rel in listOf("src/main/java", "app/src/main/java")) {
                val candidate = File(dir, rel)
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw IllegalStateException("未找到生产源码目录，源守卫无法执行（cwd=${System.getProperty("user.dir")}）")
    }
}
