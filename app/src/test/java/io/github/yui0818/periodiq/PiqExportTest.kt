package io.github.yui0818.periodiq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导出落盘通道的入站消毒哨兵（R8 第 1 轮，L3 的 P-01/P-02）。
 *
 * 判据定义：
 *  - 文件名由页内的 `<a download>` 属性提供，**属于不可信输入**：必须保证消毒后
 *    拼出来的路径仍落在导出目录内（`../` / 绝对路径 / 反斜杠 / 盘符一律被削平）。
 *  - 空 base64 与非法 base64 **必须可分辨地失败**（不允许静默成功，R2-b 的同类洞）。
 */
class PiqExportTest {

    @Test
    fun `parent traversal is flattened to a plain file name`() {
        assertEquals("eternal_backup.json", PiqExport.sanitizeName("../../eternal_backup.json"))
        assertEquals("x.json", PiqExport.sanitizeName("..\\..\\x.json"))
        assertEquals("passwd", PiqExport.sanitizeName("/etc/passwd"))
        assertEquals("x.txt", PiqExport.sanitizeName("C:\\Windows\\..\\dir\\x.txt"))
    }

    @Test
    fun `dot-only and empty names fall back to the default`() {
        assertEquals("periodiq-export", PiqExport.sanitizeName(null))
        assertEquals("periodiq-export", PiqExport.sanitizeName(""))
        assertEquals("periodiq-export", PiqExport.sanitizeName("."))
        assertEquals("periodiq-export", PiqExport.sanitizeName(".."))
        assertEquals("periodiq-export", PiqExport.sanitizeName("///"))
    }

    @Test
    fun `characters outside the allow-list become underscores`() {
        assertEquals("a_b_c.json", PiqExport.sanitizeName("a b\"c.json"))
        assertEquals("backup_2026-10-13.json", PiqExport.sanitizeName("backup_2026-10-13.json"))
        // 中文/emoji 一律换掉：宁可名字难看，也不给文件系统留歧义字符
        assertEquals("____", PiqExport.sanitizeName("备份中文"))
    }

    @Test
    fun `name length is capped`() {
        val long = "a".repeat(400) + ".json"
        assertTrue(PiqExport.sanitizeName(long).length <= PiqExport.MAX_NAME_CHARS)
    }

    @Test
    fun `sanitized name never escapes its directory`() {
        for (raw in listOf("../x", "..\\..\\x", "/x", "a/../../b", "%2e%2e%2fx", "\u0000x")) {
            val clean = PiqExport.sanitizeName(raw)
            assertFalse("消毒后仍含路径分隔符：'$raw' -> '$clean'", clean.contains('/') || clean.contains('\\'))
            assertFalse("消毒后仍是相对上级：'$raw' -> '$clean'", clean == "." || clean == "..")
        }
    }

    /**
     * R9 的 REG-08：落盘名**含到时分**且**同名不覆盖**。
     * 时间由参数注入（生产用系统时钟），断言与设备时钟无关。
     */
    @Test
    fun `export name carries date plus time and never overwrites an existing file`() {
        val dir = java.nio.file.Files.createTempDirectory("piq-export-name").toFile()
        val at = java.time.LocalDateTime.of(2026, 10, 13, 14, 35, 12)
        try {
            val first = PiqExport.allocateName(dir, PiqExport.sanitizeName("PeriodIQ-备份-20261013.json"), at)
            assertEquals("页内只给到日 ⇒ 主机补 -HHmm", "PeriodIQ-__-20261013-1435.json", first)

            // 阳性对照：文件真的存在时，第二次必须换名而不是覆盖
            java.io.File(dir, first).writeText("{}")
            val second = PiqExport.allocateName(dir, PiqExport.sanitizeName("PeriodIQ-备份-20261013.json"), at)
            assertEquals("同名必须追加 -2", "PeriodIQ-__-20261013-1435-2.json", second)
            java.io.File(dir, second).writeText("{}")
            assertEquals("再撞就 -3", "PeriodIQ-__-20261013-1435-3.json",
                PiqExport.allocateName(dir, PiqExport.sanitizeName("PeriodIQ-备份-20261013.json"), at))

            // 已经有时间尾段的名字不再补第二次（4 位 HHmm 与 6 位 HHmmss 两种）
            assertEquals("x-1435.json", PiqExport.allocateName(dir, "x-1435.json", at))
            assertEquals("x-143512.json", PiqExport.allocateName(dir, "x-143512.json", at))
            // 无扩展名 / 多扩展名：时间位补在扩展名之前，不吞掉原后缀
            assertEquals("report-1435", PiqExport.allocateName(dir, "report", at))
            assertEquals("a.b-1435.csv", PiqExport.allocateName(dir, "a.b.csv", at))
            // C5 的已知边界（`PiqExport.ensureTimePart` 的 KDoc 已登记）：4 位**编号**会被当成
            // 时间尾段而不再补时分 ⇒ 后果只是命名少一段，同名仍由上面的 `-2` 后缀兜住、不覆盖。
            assertEquals("report-0001.json", PiqExport.allocateName(dir, "report-0001.json", at))
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * 撞名分叉里"扩展名怎么切"的三种形态（R9.4 的 ③-B：删掉 `allocateName` 的恒假或支前后必须同形）。
     * 无扩展名 / 一个点 / 两个点 —— `-2` 一律插在扩展名之前，且没有点时不得凭空造出扩展名。
     */
    @Test
    fun `collision suffix splits the extension for three name shapes`() {
        val dir = java.nio.file.Files.createTempDirectory("piq-export-collide").toFile()
        val at = java.time.LocalDateTime.of(2026, 10, 13, 14, 35, 12)
        try {
            mapOf(
                "plain-1435" to "plain-1435-2",
                "one-1435.json" to "one-1435-2.json",
                "a.b-1435.json" to "a.b-1435-2.json",
            ).forEach { (existing, expect) ->
                java.io.File(dir, existing).writeText("{}")
                assertEquals("撞名后缀的位置：$existing", expect, PiqExport.allocateName(dir, existing, at))
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * L2D-F2 的**反证**：R9.2 登记的"先 `replace` 后 `take` 浪费一次全长扫描，改成先截就行"
     * 不成立 —— Java 的否定字符类把**代理对当一个码位**匹配，一次替换把 2 个 UTF-16 单元缩成 1 个 `_`，
     * 于是两序对含补充平面字符（emoji）的名字输出不同。
     *  ① 只在 BMP 内（不含代理对）时两序**逐字符相同**（8 个种子 × 200 例，反例数必须为 0）；
     *  ② 一旦出现代理对，两序**必须能不同**（下面这条给定输入必须不同，且给出两侧长度）——
     *     这一条就是"哨兵会判红"的证明：把生产代码改成先截后换，①② 一起变红。
     */
    @Test
    fun `the two sanitizing orders differ exactly at supplementary characters`() {
        val safe = Regex("[^A-Za-z0-9._-]")
        val limit = PiqExport.MAX_NAME_CHARS
        val replaceThenTake = { s: String -> s.replace(safe, "_").take(limit) }
        val takeThenReplace = { s: String -> s.take(limit).replace(safe, "_") }
        val bmpPool = ('a'..'z').toList() + ('A'..'Z').toList() + ('0'..'9').toList() +
            listOf('.', '_', '-', '/', '\\', ' ', '"', '中', '文', '备', '份')
        var mismatch = 0
        var cases = 0
        for (seed in 0 until 8) {
            val rnd = java.util.Random(20261018L + seed)
            repeat(200) {
                val raw = buildString { repeat(1 + rnd.nextInt(400)) { append(bmpPool[rnd.nextInt(bmpPool.size)]) } }
                cases++
                if (replaceThenTake(raw) != takeThenReplace(raw)) mismatch++
            }
        }
        assertEquals("样本量（8 种子 × 200 例）", 1600, cases)
        assertEquals("BMP 内两序必须逐字符相同", 0, mismatch)

        val emoji = String(Character.toChars(0x1F31F))
        val withPair = "x".repeat(94) + emoji + "yy"
        val a = replaceThenTake(withPair)
        val b = takeThenReplace(withPair)
        assertNotEquals("代理对存在时两序必须能分叉（否则这条判据是橡皮筋）", a, b)
        assertEquals("先换后截：94 个 x + 1 个下划线 + 1 个 y = 96", 96, a.length)
        assertEquals("先截后换：截到 95 个单元（代理对被切成一半）", 95, b.length)
    }

    /**
     * 顺序本身的可判红哨兵：上一条对 BMP 输入**同形**，管不住写法 ⇒ 这里直接钉生产源文件
     * （`.replace(SAFE_NAME` 必须出现在 `.take(MAX_NAME_CHARS)` **之前**）。
     * 把它换成"先截后换"⇒ 本条必须报红（变异自证的靶子）。
     */
    @Test
    fun `sanitizeName source reads replace before take`() {
        val candidates = listOf(
            "src/main/java/io/github/yui0818/periodiq/PiqExport.kt",
            "app/src/main/java/io/github/yui0818/periodiq/PiqExport.kt",
        )
        val file = candidates.map { java.io.File(it) }.firstOrNull { it.isFile }
        assertTrue("找不到生产源文件（workingDir 变了）：$candidates", file != null)
        val src = file!!.readText()
        val from = src.indexOf("internal fun sanitizeName")
        val until = src.indexOf("\n    }", from)
        assertTrue("函数体切不出来 from=$from until=$until", from >= 0 && until > from)
        val body = src.substring(from, until)
        val takeAt = body.indexOf(".take(MAX_NAME_CHARS)")
        val replaceAt = body.indexOf(".replace(SAFE_NAME")
        assertTrue("源里两处都在（take=$takeAt replace=$replaceAt）", takeAt >= 0 && replaceAt >= 0)
        assertTrue("必须先换后截（L2D-F2 驳回的结论）：take=$takeAt replace=$replaceAt", replaceAt < takeAt)
    }

    /**
     * 未覆盖声明（不留恒真断言）：`saveToDisk` 的成功/`EMPTY_PAYLOAD`/`BAD_BASE64`/`TOO_LARGE`
     * 四条分支，以及"页内脚本按 `window.PIQExport.save` 调用、桥名与方法名对得上"这件事，
     * 都需要 Android `Context`（`getExternalFilesDir`、`android.util.Base64`）与真实 WebView ⇒
     * **JVM 单测构造不出来**。本轮不写"常量等于自己"式的替身断言（`Role/执行端.md` §5.4②：
     * 观测点选错会让判据变橡皮筋），该判据归第 2 轮设备侧探针：
     * 页内点"导出备份" ⇒ `Android/data/<包名>/files/Download/` 出现文件，
     * 且文件字节与 `GET /export` 的响应内容哈希一致。
     */
}
