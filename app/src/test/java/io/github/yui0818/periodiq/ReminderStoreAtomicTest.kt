package io.github.yui0818.periodiq

import java.io.IOException
import java.nio.file.Files
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提醒计划落盘的原子替换与失败回退（R13.1 F7 / R12-P2-07）。
 *
 * 判据：
 *  - 替换阶段抛错时，已有目标必须仍是完整旧计划（不得返回空快照）；
 *  - 首次写入在替换阶段失败时，完整 `.tmp` 可被回退读取；
 *  - 成功路径读回完整新计划。
 *
 * 判红手段：把 `ReminderStoreFile.write` 改回“先 delete 再 rename”，
 * 第一条用例的 `read` 会变成空快照。
 */
class ReminderStoreAtomicTest {

    private val oldText = PiqReminderStore.encode(
        LocalDate.of(2026, 11, 8), 3, 1L, emptyList(),
    )
    private val newText = PiqReminderStore.encode(
        LocalDate.of(2026, 12, 8), 2, 2L, emptyList(),
    )

    @Test
    fun `a failed replacement leaves the old complete plan readable`() {
        val dir = Files.createTempDirectory("piq-reminder-atomic").toFile()
        val target = dir.resolve("schedule-v1.json")
        assertTrue(ReminderStoreFile.write(target, oldText, move = ::realMove))

        val ok = ReminderStoreFile.write(target, newText, move = { _, _ -> throw IOException("replace failed") })

        assertFalse(ok)
        assertEquals("替换失败后必须仍是完整旧计划", oldText, ReminderStoreFile.read(target))
    }

    @Test
    fun `a failed first replacement falls back to the complete tmp file`() {
        val dir = Files.createTempDirectory("piq-reminder-tmp").toFile()
        val target = dir.resolve("schedule-v1.json")

        val ok = ReminderStoreFile.write(target, newText, move = { _, _ -> throw IOException("replace failed") })

        assertFalse(ok)
        assertFalse("目标文件不该在失败路径被创建", target.exists())
        assertEquals("没有旧目标时必须能读到完整 tmp", newText, ReminderStoreFile.read(target))
    }

    @Test
    fun `a successful replacement is readable`() {
        val dir = Files.createTempDirectory("piq-reminder-ok").toFile()
        val target = dir.resolve("schedule-v1.json")
        assertTrue(ReminderStoreFile.write(target, oldText, move = ::realMove))
        assertTrue(ReminderStoreFile.write(target, newText, move = ::realMove))
        assertEquals(newText, ReminderStoreFile.read(target))
    }

    @Test
    fun `the production entry routes through the atomic helper`() {
        val body = ProductionSource.functionBody(
            "PiqReminderStore.kt",
            "    fun write(context: Context, predictedStart: LocalDate?, days: Int, alarms: List<PiqReminderPlan.Alarm>): Boolean",
        )
        assertTrue(
            "PiqReminderStore.write 必须走 ReminderStoreFile.write",
            body.contains("ReminderStoreFile.write(target, text)"),
        )
    }

    private fun realMove(tmp: java.io.File, target: java.io.File) {
        Files.move(
            tmp.toPath(),
            target.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
        )
    }
}
