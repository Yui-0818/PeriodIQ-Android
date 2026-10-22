package io.github.yui0818.periodiq

import android.content.res.AssetManager
import android.util.Log
import java.io.File
import java.io.IOException
import org.json.JSONObject

/**
 * 主题根判定（契约 §7.1 硬护栏：主题**缺失 / 损坏 / `minApp` 不满足 ⇒ 自动回退 `default`**）。
 *
 * 为什么必须在**选根之前**判定：`WebViewAssetLoader` 是请求时才解析路径的，根不存在不会在
 * 构建/启动期报错，只会在取回时 404 ⇒ R2-a 实测成 `chrome-error://chromewebdata/`（"网页无法打开"）。
 * 所以这里先把候选根探一遍，不可用就换默认根，并把**可读原因**交给调用方落日志。
 */
object ThemeResolver {

    const val MANIFEST = "theme.json"
    const val DEFAULT_ENTRY = "index.html"

    /** 入口"有没有内容"只需看头部字节；整份读入会让 0 字节与几 MB 走同一条路径且无上限。 */
    private const val ENTRY_HEAD_BYTES = 16 * 1024

    /**
     * UTF-8 下的空白字节集（空格 / 制表 / 换行 / 回车 / 换页）。
     * 其余字节一律算"有内容" —— 保守方向是**不误拦合法主题**（多字节空白字符极少见于入口文件首部，
     * 真出现了也只是渲染出可见字符，不属于"用户看到空白页"这一族）。
     */
    private val WHITESPACE_BYTES = byteArrayOf(0x20, 0x09, 0x0A, 0x0D, 0x0C.toByte())

    /** `minApp` 的合法形态：点分十进制（契约 §7.1 示例 `0.22.6`）；其余形态一律判清单类型/格式错。 */
    private val MIN_APP_PATTERN = Regex("^[0-9]+(\\.[0-9]+)*$")

    /**
     * 入口名的合法形态：**单个**安全路径段（字母数字开头，只允许 `字母 数字 . _ -`）。
     *
     * 为什么必须有这条（L2D-F3）：入口名会被用在两个地方 ——
     * ① 文件系统侧 `File(dir, entry)` / `assets.open(prefix + entry)`，② `MainActivity` 拼成的
     * URL `https://appassets.androidplatform.net/theme/<entry>`。
     * `#` 与 `?` 在第一处是**合法文件名字符**（文件真的存在、真的有内容 ⇒ 探测会放行），
     * 在第二处却分别是"片段"与"查询"的开始（被截掉）⇒ 加载到的是另一个路径 ⇒ 404/空白页。
     * 两处判据不同一才是这条缺陷的内容，所以闸门必须卡在**选根之前**、由 [probe] 统一判。
     * `/`、`\`、`..` 同族：文件系统会越出主题根，URL 侧则被 `WebViewAssetLoader` 归一化成另一条路径。
     */
    private val ENTRY_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")

    /**
     * 入口名是否可以用在同一条路径上（文件系统与 URL 两个去处同一判据）。
     *
     * `ENTRY_PATTERN` 要求**首字符是字母或数字**，这一条就把 `..`、`.hidden`、空名全部排除在外，
     * 再加上禁 `/` 与 `\` ⇒ 合法入口名只能是主题根目录下的一个直接子文件，不可能越根。
     *
     * `internal` 是给哨兵留的**生产 seam**（同 R9.4 的 `predictionsChronologicalSql`）：
     * `resolve()` 需要 `AssetManager`，JVM 单测造不出来，形态判定本身必须是可单测的纯函数。
     */
    internal fun isSingleSafeEntry(entry: String): Boolean = ENTRY_PATTERN.matches(entry)

    private fun ByteArray.hasContent(): Boolean = any { !WHITESPACE_BYTES.contains(it) }

    /**
     * 按 `variant` 选出**生效根**。`default` 直接给内置根；其余 variant 依次探测候选根：
     * ① `files/themes/<variant>/`（契约 §7.1 的安装位置）② 内置 `assets/www-custom/`（R1 起的映射）。
     * 全部不可用 ⇒ 回退内置默认根并给出 [ThemeDecision.fallbackReason]。
     */
    fun resolve(assets: AssetManager, themesDir: File, variant: String, appVersion: String): ThemeDecision {
        val defaultRoot = ThemeRoot.Assets(VariantPolicy.rootOf(VariantPolicy.DEFAULT))
        if (variant == VariantPolicy.DEFAULT) {
            // 回退目标本身也要留读数（R2-b 报告 §6-11 的证据缺口：默认根从不进 probe）
            describeDefaultRoot(assets, defaultRoot)
            return ThemeDecision(variant, defaultRoot, DEFAULT_ENTRY, null, "requested=default")
        }
        val candidates = listOf(
            ThemeRoot.Installed(File(themesDir, variant), variant),
            ThemeRoot.Assets(VariantPolicy.rootOf(variant)),
        )
        val reasons = StringBuilder()
        for (candidate in candidates) {
            val verdict = probe(assets, candidate, appVersion)
            if (reasons.isNotEmpty()) reasons.append(" ;; ")
            reasons.append("${label(candidate)} -> ${verdict ?: "usable"}")
            if (verdict == null) {
                return ThemeDecision(variant, candidate, entryOf(assets, candidate), null, reasons.toString())
            }
        }
        return ThemeDecision(variant, defaultRoot, DEFAULT_ENTRY, "all_candidates_unusable", reasons.toString())
    }

    /**
     * 判据显式化（`AGENTS.md` §3.4 第 4 道闸门）。返回 null = 可用；否则返回**不可用原因**。
     * 命中顺序：根不存在/空 → 清单缺失 → 清单空文件 → 清单不可解析 → 清单字段类型错
     * → 入口文件缺失/0 字节/只有空白字符 → minApp 不满足。
     */
    private fun probe(assets: AssetManager, root: ThemeRoot, appVersion: String): String? {
        val listing = listingOf(assets, root)
        if (listing == null) return "root_absent"
        if (listing.isEmpty()) return "root_empty"
        if (!listing.contains(MANIFEST)) return "manifest_missing(entries=${listing.size})"
        val raw = readText(assets, root, MANIFEST)
            ?: return "manifest_unreadable"
        if (raw.isEmpty()) return "manifest_empty"
        val manifest = runCatching { JSONObject(raw) }.getOrNull()
            ?: return "manifest_unparseable(len=${raw.length})"
        if (manifest.has("minApp") && manifest.isNull("minApp")) return "manifest_minApp_null"
        if (!manifest.has("entry")) return "manifest_entry_missing"
        val entry = manifest.opt("entry") as? String
            ?: return "manifest_entry_type(actual=${manifest.opt("entry")?.javaClass?.simpleName ?: "null"})"
        if (entry.isBlank()) return "manifest_entry_blank"
        // 形态闸门必须在**文件系统探测之前**（L2D-F3）：`a#b.html` 这类名字在文件系统侧是真的
        // 存在且有内容，一旦被放行就会流到 URL 拼接处被截成另一个资源 ⇒ 用户看到空白页。
        if (!isSingleSafeEntry(entry)) {
            return "entry_malformed(declared=${JSONObject.quote(entry)})"
        }
        val head = entryHead(assets, root, entry)
            ?: return "entry_missing(entry=$entry)"
        if (head.isEmpty()) return "entry_zero_bytes(entry=$entry)"
        if (!head.hasContent()) return "entry_whitespace_only(entry=$entry,head_bytes=${head.size})"
        // minApp 可省略（= 不设门槛）；一旦给出就必须是**点分十进制字符串**：
        // 之前用 optString 会把 `{"minApp":{"a":1}}` 这类类型错静默当成"无约束"（R2-c 缺陷 A 的一支）。
        if (manifest.has("minApp")) {
            val declared = manifest.opt("minApp")
            if (declared !is String) {
                return "manifest_minApp_type(actual=${declared?.javaClass?.simpleName ?: "null"})"
            }
            if (!MIN_APP_PATTERN.matches(declared)) return "manifest_minApp_malformed(value=$declared)"
            if (!versionAtLeast(appVersion, declared)) {
                return "min_app_unsatisfied(minApp=$declared,appVersion=$appVersion)"
            }
        }
        return null
    }

    /**
     * 入口文件头部字节；null = 取不到（不存在或读失败）。
     * "看似存在但渲染出来是空白"的两种形态（0 字节 / 全空白字符）都在这里分辨，
     * 判定口径：头部 16 KiB 内存在任一非空白字符才算有内容。
     */
    private fun entryHead(assets: AssetManager, root: ThemeRoot, relative: String): ByteArray? = try {
        when (root) {
            is ThemeRoot.Assets -> assets.open(AssetRootHandler.join(root.prefix, relative))
                .use { readHead(it, ENTRY_HEAD_BYTES) }
            is ThemeRoot.Installed -> File(root.dir, relative)
                .takeIf { it.isFile }?.inputStream()?.use { readHead(it, ENTRY_HEAD_BYTES) }
        }
    } catch (e: IOException) {
        Log.d("PIQ", "theme-entry-read-failed|root=${label(root)}|path=$relative|err=${e.message}")
        null
    }

    private fun readHead(stream: java.io.InputStream, limit: Int): ByteArray {
        val buffer = ByteArray(limit)
        var filled = 0
        while (filled < limit) {
            val read = stream.read(buffer, filled, limit - filled)
            if (read < 0) break
            filled += read
        }
        return buffer.copyOf(filled)
    }

    /** 默认根（回退目标）的读数：只落日志，不参与判定 —— 没有它可回退时至少要留下证据。 */
    private fun describeDefaultRoot(assets: AssetManager, root: ThemeRoot) {
        val listing = listingOf(assets, root)
        val head = entryHead(assets, root, DEFAULT_ENTRY)
        val state = when {
            listing == null || listing.isEmpty() -> "root_unreadable(listing=${listing?.size ?: "null"})"
            head == null -> "entry_missing(entry=$DEFAULT_ENTRY)"
            head.isEmpty() -> "entry_zero_bytes(entry=$DEFAULT_ENTRY)"
            !head.hasContent() -> "entry_whitespace_only(entry=$DEFAULT_ENTRY)"
            else -> "entry_ok(head_bytes=${head.size})"
        }
        Log.i("PIQ", "theme-default-root|root=${label(root)}|listing=${listing?.size ?: "null"}|$state")
    }

    private fun entryOf(assets: AssetManager, root: ThemeRoot): String {
        val raw = readText(assets, root, MANIFEST) ?: return DEFAULT_ENTRY
        val manifest = runCatching { JSONObject(raw) }.getOrNull() ?: return DEFAULT_ENTRY
        return (manifest.opt("entry") as? String)?.takeIf { it.isNotBlank() } ?: DEFAULT_ENTRY
    }

    /** 点分十进制版本比较：逐段取前导数字，缺段按 0；`app >= minApp` 才算满足。 */
    fun versionAtLeast(app: String, minApp: String): Boolean {
        val a = segments(app)
        val b = segments(minApp)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return true
    }

    private fun segments(version: String): List<Int> =
        version.split('.').map { it.trim().takeWhile { c -> c.isDigit() }.toIntOrNull() ?: 0 }

    /** 主题根的日志与处理器标识：assets 根为 `assets:<前缀>`、已装根为 `installed:<themeId>`（`MainActivity` 用它给 `FileRootHandler` 命名）。 */
    fun label(root: ThemeRoot): String = when (root) {
        is ThemeRoot.Assets -> "assets:${root.prefix}"
        is ThemeRoot.Installed -> "installed:${root.themeId}"
    }

    /**
     * 取根下文件清单。Assets 侧 `AssetManager.list()` 对**带尾斜杠**的前缀返回空数组
     * （R2-b 报告 §6-11 的成因之一），故查询词一律去掉尾斜杠；两种根都把清单大小落日志，
     * 让"根不存在"与"取清单抛异常"在读数上可分辨（§6-13）。
     */
    private fun listingOf(assets: AssetManager, root: ThemeRoot): List<String>? = when (root) {
        is ThemeRoot.Assets -> {
            val query = root.prefix.trimEnd('/')
            runCatching { assets.list(query) }
                .onFailure { Log.w("PIQ", "theme-listing-failed|root=${label(root)}|query=$query|err=${it.message}") }
                .getOrNull()?.toList()
                ?.also { Log.i("PIQ", "theme-listing|root=${label(root)}|query=$query|count=${it.size}") }
        }
        is ThemeRoot.Installed -> {
            if (!root.dir.isDirectory) {
                Log.i("PIQ", "theme-listing|root=${label(root)}|query=${root.dir.path}|count=absent")
                null
            } else {
                (root.dir.list() ?: emptyArray()).toList()
                    .also { Log.i("PIQ", "theme-listing|root=${label(root)}|count=${it.size}") }
            }
        }
    }

    private fun readText(assets: AssetManager, root: ThemeRoot, relative: String): String? = try {
        when (root) {
            is ThemeRoot.Assets -> assets.open(AssetRootHandler.join(root.prefix, relative)).use { it.readBytes() }
            is ThemeRoot.Installed -> File(root.dir, relative).takeIf { it.isFile }?.readBytes()
        }?.toString(Charsets.UTF_8)
    } catch (e: IOException) {
        Log.d("PIQ", "theme-probe-read-failed|root=${label(root)}|path=$relative|err=${e.message}")
        null
    }
}

/** 主题根的位置（契约 §7.1：内置资产 + 可安装目录两种）。 */
sealed class ThemeRoot {

    /** APK 内 `assets/` 下的目录前缀（恒以 `/` 结尾）。 */
    data class Assets(val prefix: String) : ThemeRoot()

    /** 安装到应用私有目录的主题包：`files/themes/<id>/`。 */
    data class Installed(val dir: File, val themeId: String) : ThemeRoot()
}

/**
 * [ThemeResolver.resolve] 的结果。
 *
 * @param requested 用户请求的 variant
 * @param root 生效根（回退时为内置默认根）
 * @param entry 主题清单声明的入口文件名
 * @param fallbackReason 非 null = 发生了回退，内容可直接落日志
 * @param evidence 每个候选根的探测结果原文（含未回退的情况）
 */
data class ThemeDecision(
    val requested: String,
    val root: ThemeRoot,
    val entry: String,
    val fallbackReason: String?,
    val evidence: String,
)
