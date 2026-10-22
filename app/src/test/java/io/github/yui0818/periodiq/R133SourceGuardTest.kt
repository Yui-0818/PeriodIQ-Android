package io.github.yui0818.periodiq

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R13.3 的不可回退合同（源扫描）：通知 Intent 只带 id、登录后重触发日历路由、
 * provider 只读且不导出、另存为请求码不与文件选择器相撞、权限集合不变。
 */
class R133SourceGuardTest {

    @Test
    fun `notification intent carries only the notify id`() {
        val body = ProductionSource.functionBody("PiqReminderScheduler.kt", "fun fireNotification(")
        val start = body.indexOf("PendingIntent.getActivity(")
        val end = body.indexOf("val notification =")
        assertTrue("找不到 contentIntent 构造段，源守卫已失效", start >= 0 && end > start)
        val intentBlock = body.substring(start, end)
        assertTrue("contentIntent 必须带 EXTRA_NOTIFY_ID", intentBlock.contains("putExtra(EXTRA_NOTIFY_ID, notifyId)"))
        for (forbidden in listOf("predicted", "period_start", "trigger_date")) {
            assertFalse("Intent 不得携带日期事实：$forbidden", intentBlock.contains(forbidden))
        }
        val scheduler = ProductionSource.commentsRemoved("PiqReminderScheduler.kt")
        assertTrue(scheduler.contains("EXTRA_NOTIFY_ID = \"notify_id\""))
    }

    @Test
    fun `calendar route is applied and retriggered after login`() {
        val activity = ProductionSource.commentsRemoved("MainActivity.kt")
        assertTrue("缺少日历路由目标", activity.contains("#/calendar"))
        val body = ProductionSource.functionBody("MainActivity.kt", "private fun afterApiServed(")
        assertTrue("登录 200 未接重触发", body.contains("/auth/login"))
        assertTrue("注册 200 未接重触发", body.contains("/auth/register"))
        assertTrue("重触发必须以 pending 为前提", body.contains("pendingCalendarNotifyId != 0"))
        assertTrue("缺少 applyPendingCalendarRoute 调用", body.contains("applyPendingCalendarRoute()"))
    }

    @Test
    fun `provider is private read only and resolves safe paths`() {
        val provider = ProductionSource.read("PiqExportProvider.kt")
        assertTrue(provider.contains("PiqExport.resolveExportFile("))
        assertTrue(provider.contains("ParcelFileDescriptor.MODE_READ_ONLY"))
        assertTrue("openFile 必须拒绝非只读模式", provider.contains("mode != \"r\""))
        val manifest = readManifest()
        val tag = manifest.substringAfter("<provider").substringBefore("/>")
        assertTrue(tag.contains("android:name=\".PiqExportProvider\""))
        assertTrue(tag.contains("android:exported=\"false\""))
        assertTrue(tag.contains("android:grantUriPermissions=\"true\""))
        assertTrue(tag.contains("android:authorities=\"\${applicationId}.exports\""))
        val permissions = Regex("""<uses-permission[^>]*android:name="([^"]+)"""")
            .findAll(manifest).map { it.groupValues[1] }.toSet()
        assertEquals(
            setOf("android.permission.POST_NOTIFICATIONS", "android.permission.RECEIVE_BOOT_COMPLETED"),
            permissions,
        )
    }

    @Test
    fun `save as request code differs from the file chooser code`() {
        val export = ProductionSource.commentsRemoved("PiqExport.kt")
        val chooser = ProductionSource.commentsRemoved("PiqFileChooser.kt")
        val save = Regex("""SAVE_AS_REQUEST_CODE\s*=\s*0x([0-9A-Fa-f]+)""").find(export)?.groupValues?.get(1)?.toInt(16)
        val pick = Regex("""REQUEST_CODE\s*=\s*0x([0-9A-Fa-f]+)""").find(chooser)?.groupValues?.get(1)?.toInt(16)
        assertNotNull("SAVE_AS_REQUEST_CODE 缺失", save)
        assertNotNull("文件选择器 REQUEST_CODE 缺失", pick)
        assertEquals(0x51C1, save)
        assertNotEquals(pick, save)
        val bridge = ProductionSource.functionBody("PiqExport.kt", "fun saveWithToken(")
        assertTrue(
            "origin 校验必须先于落盘",
            bridge.indexOf("BridgeOriginGuard.isTrusted") in 0 until bridge.indexOf("saveToDisk("),
        )
    }

    /** manifest 不在 `src/main/java` 下，[ProductionSource] 的源目录读数器找不到它。 */
    private fun readManifest(): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            for (rel in listOf("app/src/main/AndroidManifest.xml", "src/main/AndroidManifest.xml")) {
                val file = File(dir, rel)
                if (file.isFile) return file.readText()
            }
            dir = dir.parentFile
        }
        throw IllegalStateException("找不到 AndroidManifest.xml，源守卫已失效")
    }
}
