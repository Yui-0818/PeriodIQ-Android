package io.github.yui0818.periodiq

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume
import org.junit.Test

/**
 * 导出路径安全（R13.3）：provider 与分享共用 [PiqExport.resolveExportFile]。
 * 判据：只接受导出目录下的普通文件；穿越、绝对路径、目录、符号链接一律拒绝。
 */
class ExportPathSafetyTest {

    private fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile()

    @Test
    fun `normal file inside export dir is accepted`() {
        val dir = tempDir("piq-export-ok")
        val file = File(dir, "backup.json").apply { writeText("{}") }
        assertEquals(file.canonicalFile, PiqExport.resolveExportFile(dir, "backup.json"))
    }

    @Test
    fun `traversal and absolute names are rejected`() {
        val dir = tempDir("piq-export-traversal")
        for (name in listOf("../x", "..\\x", "a/../../b", "/etc/passwd", "C:\\Windows\\x", "a/b", ".", "..", "")) {
            assertNull("必须拒绝：$name", PiqExport.resolveExportFile(dir, name))
        }
    }

    @Test
    fun `directories are rejected`() {
        val dir = tempDir("piq-export-dir")
        File(dir, "sub").mkdirs()
        assertNull(PiqExport.resolveExportFile(dir, "sub"))
    }

    @Test
    fun `symbolic links are rejected`() {
        val dir = tempDir("piq-export-link")
        val outside = File(tempDir("piq-export-outside"), "secret.json").apply { writeText("secret") }
        val link = File(dir, "link.json")
        try {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
        } catch (e: Exception) {
            Assume.assumeNoException("本机不允许创建符号链接，符号链接腿跳过", e)
        }
        assertNull(PiqExport.resolveExportFile(dir, "link.json"))
    }
}
