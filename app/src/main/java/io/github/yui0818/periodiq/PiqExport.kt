package io.github.yui0818.periodiq

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.widget.Toast
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import org.json.JSONObject

/**
 * 导出落盘通道（R8 第 1 轮，L3 的 P-01/P-02）。
 *
 * **缺陷形态**：前端把备份 JSON 与 CSV 做成 `Blob` + `<a download>` 触发下载，
 * 而宿主没有接任何下载出口 ⇒ Android WebView 对该请求**静默丢弃**，用户点"导出备份"
 * 之后什么都没发生（`AGENTS.md` §6 第 1 条要求安卓端对 JSON 备份"必须能读**能写**"，
 * 端点 `GET /export` 有实现但文件落不了地，等于不能写）。
 *
 * **为什么不走系统下载管理器/分享通道**：本工程零权限（不申请 `INTERNET`、
 * 不申请任何存储权限，`AGENTS.md` §5 第 4 条），而 `blob:` URL 只在页内可读、
 * 宿主拿不到字节。所以通道分两段：
 * ① [SCRIPT] 在文档起始处包装 `URL.createObjectURL` 与 `<a download>` 的点击，
 *    用 `FileReader` 把 Blob 读成 base64，交给桥方法 [BRIDGE_NAME]；
 * ② [Bridge.save] 把 base64 解码后写入**应用外部私有目录**
 *    `Android/data/<包名>/files/Download/`（`getExternalFilesDir` 不需要任何权限，
 *    卸载即随应用清除），并给用户一条可见回执。
 *
 * 门控归属：这段脚本只在安卓宿主里注入，桌面无此路径（契约 §4 的"门控只影响行为"）。
 */
object PiqExport {

    const val BRIDGE_NAME = "PIQExport"

    /** 桥方法名（页内调用 `window.PIQExport.save(name, base64, mime)`）。 */
    const val METHOD_SAVE = "save"

    private const val TAG = "PIQ"

    /**
     * 单次导出的字节上限（base64 解码后）。超上限**必须报错并回执**，
     * 不允许"静默返回 null"——那是本项目在 R2-b 踩过的同一类洞（超限即跳过 = 看不见失败）。
     */
    const val MAX_EXPORT_BYTES = 64L * 1024 * 1024

    /** 另存为（SAF）的请求码。与文件选择器的 `0x51C0` 必须不同。 */
    const val SAVE_AS_REQUEST_CODE = 0x51C1

    /** base64 末尾允许的最大 `=` 数；超过即结构非法，解码前直接拒绝。 */
    private const val BASE64_PADDING_MAX = 2

    /** 文件名的合法形态：只保留 `字母 数字 . _ -`，其余字符一律替换为 `_`（防 `../` 越出导出目录）。 */
    private val SAFE_NAME = Regex("[^A-Za-z0-9._-]")
    internal const val MAX_NAME_CHARS = 96

    /** 落盘名里补的"时分"位（REG-08：页内只给到日 ⇒ 同日二次导出会撞名）。分隔符由拼接处给。 */
    private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HHmm")

    /** 名字尾部已经是时间（6 位 `HHmmss` 或 4 位 `HHmm`）就不再补第二次。 */
    private val TIME_TAIL = Regex("-(?:\\d{6}|\\d{4})$")

    /** 同名不覆盖时最多试到 `-99`；再挤就用毫秒数兜底（这一条就是 [MAX_COLLISION_TRIES] 自身的契约）。
     * 撞名事实不在这里给：`allocateName` 自己不打日志（保持纯 JVM 可单测），所以证据在调用点
     * `export|saved|name=<终名>|requested=<请求名>` 那两列里 —— 它属于 `allocateName`，不属于本常量。 */
    private const val MAX_COLLISION_TRIES = 99

    /**
     * 页内注入脚本。三件事，每件都**失败可分辨**：
     * ① 包装 `URL.createObjectURL` 以记住 url→Blob 的对应关系；
     * ② 捕获阶段拦截 `<a download>` 的点击，读出字节并经桥落盘；
     * ③ 任何一步做不到（拿不到 Blob / 读失败 / 桥不存在）都打 `console.warn`，
     *    让"没导出"与"导出了"在 logcat 上可分辨（宿主已把 console 转成日志）。
     */
    val SCRIPT: String = """
        (function () {
            if (window.__piqExportInstalled) { return; }
            window.__piqExportInstalled = true;
            var registry = new Map();
            var create = URL.createObjectURL.bind(URL);
            URL.createObjectURL = function (obj) {
                var url = create(obj);
                if (obj instanceof Blob) { registry.set(url, obj); }
                return url;
            };
            // L2E-05：页内撤销 blob 时把登记一起撤掉（registry 原来只 set 不 delete ⇒
            // 每导出一份就有一个 Blob 被这张表钉到页面卸载为止）。
            // 为什么不在"落盘成功"时就删：那会让同一枚锚点的第二次点击拿不到 Blob，
            // 从"能重复导出"退化成"静默失败"（R9.4 台账 §6 记的那条顾虑）。
            // 删的时机交给页面自己的 revoke —— 它 revoke 了这枚 url，第二次点击本来也就没意义了。
            var revoke = URL.revokeObjectURL.bind(URL);
            URL.revokeObjectURL = function (url) {
                var key = String(url);
                var evicted = registry.delete(key);
                revoke(key);
                if (evicted) { console.info('[piq-export] registry-evict|size=' + registry.size); }
            };
            document.addEventListener('click', function (ev) {
                var anchor = ev.target && ev.target.closest ? ev.target.closest('a[download]') : null;
                if (!anchor) { return; }
                var url = anchor.getAttribute('href') || '';
                if (url.indexOf('blob:') !== 0) { return; }
                var blob = registry.get(url);
                if (!blob) {
                    console.warn('[piq-export] no-blob-registered url=' + url + ' => 未承接，交给默认行为');
                    return;
                }
                ev.preventDefault();
                if (!window.$BRIDGE_NAME || typeof window.$BRIDGE_NAME.$METHOD_SAVE !== 'function') {
                    console.warn('[piq-export] bridge-absent => 导出不可用');
                    return;
                }
                var name = anchor.getAttribute('download') || 'periodiq-export';
                var reader = new FileReader();
                reader.onload = function () {
                    var comma = String(reader.result).indexOf(',');
                    var payload = comma < 0 ? '' : String(reader.result).slice(comma + 1);
                    try {
                        console.log('[piq-export] bridge|nameLen=' + name.length + '|b64len=' + payload.length);
                        window.$BRIDGE_NAME.$METHOD_SAVE(name, payload, blob.type || '');
                    } catch (e) {
                        console.warn('[piq-export] bridge-threw|' + e);
                    }
                };
                reader.onerror = function () {
                    console.warn('[piq-export] read-failed|' + reader.error);
                };
                reader.readAsDataURL(blob);
            }, true);
        })();
    """.trimIndent()

    /** 桥实现（由 [MainActivity] 用 `addJavascriptInterface` 注册为 [BRIDGE_NAME]）。 */
    class Bridge(
        private val activity: Activity,
        private val currentUrl: () -> String?,
        private val expectedToken: () -> String,
    ) {
        /** 旧的三参入口：没有令牌，直接 403（`data:` 文档的普通调用也能留下可观测拒绝）。 */
        @JavascriptInterface
        fun save(name: String?, base64: String?, mime: String?): String {
            Log.w(TAG, "export|rejected|reason=missing-token|nameLen=${sanitizeName(name).length}")
            return result(false, sanitizeName(name), 0L, "FORBIDDEN_ORIGIN", 403).toString()
        }

        @JavascriptInterface
        fun saveWithToken(name: String?, base64: String?, mime: String?, token: String?): String {
            val trusted = BridgeOriginGuard.isTrusted(currentUrl())
            val tokenOk = token != null && token == expectedToken()
            if (!trusted || !tokenOk) {
                val reason = if (trusted) "bad-token" else "forbidden-origin"
                Log.w(TAG, "export|rejected|reason=$reason|nameLen=${sanitizeName(name).length}")
                return result(false, sanitizeName(name), 0L, "FORBIDDEN_ORIGIN", 403).toString()
            }
            return saveToDisk(activity, name, base64, mime) { file, mimeType ->
                showExportOptions(activity, file, mimeType)
            }.toString()
        }
    }

    /**
     * 先按 base64 长度估算解码后字节数，超限时**不调用**解码函数（R13.1 F4 / R12-P2-04）。
     * 估算取上界（向上取整到 4 字符一组），合法输入与实际字节数相等。
     * 结构非法的 `=`（出现在中间、超过 2 个、带 padding 但长度不是 4 的倍数）在
     * [decodeWithinLimit] 里先判 [LimitedPayload.BadBase64]，不会进入估算或解码（R13.1.1 R06）。
     */
    internal sealed class LimitedPayload {
        data class Bytes(val bytes: ByteArray) : LimitedPayload()
        data class TooLarge(val estimated: Long) : LimitedPayload()
        object Empty : LimitedPayload()
        object BadBase64 : LimitedPayload()
    }

    internal fun estimatedDecodedBytes(base64: String): Long {
        var chars = 0L
        var padding = 0
        for (c in base64) {
            if (c == '\n' || c == '\r' || c == ' ' || c == '\t') continue
            chars += 1
            if (c == '=') padding += 1
        }
        if (chars == 0L) return 0
        return ((chars + 3) / 4) * 3 - padding
    }

    /** `=` 只能在尾部且最多 [BASE64_PADDING_MAX] 个；带 padding 时总长必须是 4 的倍数。 */
    internal fun structurallyValidBase64(payload: String): Boolean {
        var chars = 0
        var padding = 0
        var seenPadding = false
        for (c in payload) {
            if (c == '\n' || c == '\r' || c == ' ' || c == '\t') continue
            chars += 1
            if (c == '=') {
                seenPadding = true
                padding += 1
                if (padding > BASE64_PADDING_MAX) return false
            } else if (seenPadding) {
                return false
            }
        }
        return padding == 0 || chars % 4 == 0
    }

    internal fun decodeWithinLimit(
        payload: String,
        maxBytes: Long,
        decode: (String) -> ByteArray?,
    ): LimitedPayload {
        if (payload.isEmpty()) return LimitedPayload.Empty
        if (!structurallyValidBase64(payload)) return LimitedPayload.BadBase64
        val estimated = estimatedDecodedBytes(payload)
        if (estimated > maxBytes) return LimitedPayload.TooLarge(estimated)
        val bytes = decode(payload) ?: return LimitedPayload.BadBase64
        if (bytes.size.toLong() > maxBytes) return LimitedPayload.TooLarge(bytes.size.toLong())
        return LimitedPayload.Bytes(bytes)
    }

    /** 导出落点：应用外部私有目录下的 `Download/`，不可用时退回内部 `files/exports/`。 */
    internal fun exportDir(context: Context): File {
        val external = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
        return external ?: File(context.filesDir, "exports").also { it.mkdirs() }
    }

    /**
     * provider 的 authority 与 URI 构造（share 与源守卫共用）。
     * 名字必须 URL 编码，避免空格/中文在 URI 解析时改变路径段。
     */
    internal fun exportAuthority(context: Context): String = "${context.packageName}.exports"

    internal fun exportContentUri(context: Context, name: String): Uri =
        Uri.parse("content://${exportAuthority(context)}/${Uri.encode(name)}")

    /**
     * 只接受导出目录下的普通文件（R13.3 的安全核心）。
     * 拒绝：空名/点号名、绝对路径、盘符、含 `/` `\` 或 `..`、目录、符号链接、canonical 逃逸。
     */
    internal fun resolveExportFile(dir: File, rawName: String): File? {
        if (rawName.isEmpty() || rawName == "." || rawName == "..") return null
        if (rawName.contains('/') || rawName.contains('\\') || rawName.contains("..")) return null
        if (rawName.contains(':') || File(rawName).isAbsolute) return null
        val root = runCatching { dir.canonicalFile }.getOrNull() ?: return null
        val candidate = File(root, rawName)
        if (!candidate.isFile) return null
        val symlink = runCatching { java.nio.file.Files.isSymbolicLink(candidate.toPath()) }.getOrDefault(true)
        if (symlink) return null
        val canonical = runCatching { candidate.canonicalFile }.getOrNull() ?: return null
        if (!canonical.toPath().startsWith(root.toPath())) return null
        return canonical
    }

    /**
     * 写盘。返回给页内的 JSON 只含**长度与状态**，不含文件内容
     * （`AGENTS.md` §5：日志与回执里都不得出现用户数据）。
     * [onSaved] 只在写盘成功且成功回执之后触发，异常只记日志、不改写盘结果。
     */
    fun saveToDisk(
        context: Context,
        rawName: String?,
        base64: String?,
        mime: String?,
        onSaved: ((File, String) -> Unit)? = null,
    ): JSONObject {
        val name = sanitizeName(rawName)
        val payload = base64.orEmpty()
        if (payload.isEmpty()) {
            return rejected(context, name, 0L, "EMPTY_PAYLOAD", "empty-payload")
        }
        val decoded = decodeWithinLimit(payload, MAX_EXPORT_BYTES) {
            runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull()
        }
        val bytes = when (decoded) {
            is LimitedPayload.TooLarge ->
                return rejected(context, name, decoded.estimated, "TOO_LARGE", "over-limit-precheck")
            LimitedPayload.BadBase64 ->
                return rejected(context, name, 0L, "BAD_BASE64", "bad-base64")
            LimitedPayload.Empty ->
                return rejected(context, name, 0L, "EMPTY_PAYLOAD", "empty-payload")
            is LimitedPayload.Bytes -> decoded.bytes
        }
        if (bytes.size.toLong() > MAX_EXPORT_BYTES) {
            return rejected(context, name, bytes.size.toLong(), "TOO_LARGE", "over-limit")
        }
        val dir = exportDir(context)
        val target = File(dir, allocateName(dir, name))
        val envelope = runCatching {
            target.outputStream().use { it.write(bytes) }
            val finalName = target.name
            Log.i(TAG, "export|saved|finalNameLen=${finalName.length}|requestedLen=${name.length}|bytes=${bytes.size}|mime=${mime.orEmpty()}|dir=${dir.name}")
            postReceipt(context, finalName, bytes.size.toLong())
            result(true, finalName, bytes.size.toLong(), "OK")
        }.getOrElse { exc ->
            Log.w(TAG, "export|write-failed|nameLen=${name.length}|type=${exc.javaClass.simpleName}")
            postFailure(context, name, "WRITE_FAILED", "write-failed|type=${exc.javaClass.simpleName}")
            return result(false, name, bytes.size.toLong(), "WRITE_FAILED")
        }
        onSaved?.let { callback ->
            Handler(Looper.getMainLooper()).post {
                runCatching { callback(target, mime.orEmpty()) }
                    .onFailure { Log.w(TAG, "export|saved-callback-failed|type=${it.javaClass.simpleName}") }
            }
        }
        return envelope
    }

    /** 导出成功后的原生选择：分享 / 另存为 / 完成。主线程调用。 */
    internal fun showExportOptions(activity: Activity, file: File, mime: String) {
        val size = if (file.length() >= 1024) "${file.length() / 1024} KB" else "${file.length()} B"
        AlertDialog.Builder(activity)
            .setTitle("已导出 ${file.name}")
            .setMessage("${file.name}（$size）\n备份含周期数据，请只分享给可信应用。")
            .setPositiveButton("分享") { _, _ -> shareFile(activity, file, mime) }
            .setNeutralButton("另存为") { _, _ -> startSaveAs(activity, file, mime) }
            .setNegativeButton("完成", null)
            .show()
    }

    /** `ACTION_SEND` + 单 URI 只读授权；没有分享应用时给可读回执。 */
    internal fun shareFile(activity: Activity, file: File, mime: String) {
        val uri = exportContentUri(activity, file.name)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime.ifBlank { "application/octet-stream" }
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(activity.contentResolver, file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            activity.startActivity(Intent.createChooser(send, "分享导出文件"))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "export|share|no-app|mime=${send.type}|bytes=${file.length()}")
            Toast.makeText(activity, "没有可用的分享应用", Toast.LENGTH_LONG).show()
        }
    }

    /** 另存为只记源文件与展示名，**不缓存字节**（SAF 返回后再流式复制）。 */
    private data class PendingSaveAs(val file: File, val mime: String, val name: String)

    private var pendingSaveAs: PendingSaveAs? = null

    internal fun startSaveAs(activity: Activity, file: File, mime: String) {
        val name = sanitizeName(file.name)
        pendingSaveAs = PendingSaveAs(file, mime, name)
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime.ifBlank { "application/octet-stream" }
            putExtra(Intent.EXTRA_TITLE, name)
        }
        activity.startActivityForResult(intent, SAVE_AS_REQUEST_CODE)
    }

    /** SAF 返回：流式复制；取消/失败/进程重建都只给可读回执，不崩溃。 */
    fun finishSaveAs(activity: Activity, resultCode: Int, data: Intent?) {
        val pending = pendingSaveAs
        pendingSaveAs = null
        if (resultCode != Activity.RESULT_OK) {
            if (pending != null) {
                Log.i(TAG, "export|save-as|canceled|nameLen=${pending.name.length}")
                Toast.makeText(activity, "已取消保存", Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (pending == null) {
            Log.w(TAG, "export|save-as|lost-pending")
            Toast.makeText(activity, "保存未完成，请重试", Toast.LENGTH_LONG).show()
            return
        }
        val uri = data?.data
        if (uri == null) {
            Log.w(TAG, "export|save-as|no-uri|nameLen=${pending.name.length}")
            Toast.makeText(activity, "保存失败：没有拿到目标位置", Toast.LENGTH_LONG).show()
            return
        }
        val copied = runCatching {
            activity.contentResolver.openOutputStream(uri)?.use { out ->
                pending.file.inputStream().use { input -> input.copyTo(out) }
            } ?: error("openOutputStream returned null")
        }
        if (copied.isSuccess) {
            Log.i(TAG, "export|save-as|ok|nameLen=${pending.name.length}|bytes=${pending.file.length()}|mime=${pending.mime}")
            Toast.makeText(activity, "已保存 ${pending.name}", Toast.LENGTH_LONG).show()
        } else {
            Log.w(TAG, "export|save-as|failed|nameLen=${pending.name.length}|type=${copied.exceptionOrNull()?.javaClass?.simpleName}")
            Toast.makeText(activity, "保存失败，请重试", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 失败分支统一从这里走（R9.2 的 L2E-04）：原来只有成功分支给用户回执，
     * 而页内**不读桥的返回值**（快照里 `<a download>` 那条腿点完就不管了）⇒
     * 写盘失败/超限/载荷不合法时用户看到的和"什么都没发生"完全一样。
     */
    private fun rejected(
        context: Context,
        name: String,
        bytes: Long,
        code: String,
        reason: String,
    ): JSONObject {
        Log.w(TAG, "export|rejected|nameLen=${name.length}|bytes=$bytes|reason=$reason|code=$code")
        postFailure(context, name, code, reason)
        return result(false, name, bytes, code)
    }

    /**
     * 文件名消毒：先剥掉任何目录部分，再把非 `[A-Za-z0-9._-]` 一律换成 `_`，
     * 并拒绝空名与纯点号名（`.`/`..`）。长度截到 [MAX_NAME_CHARS]。
     *
     * ⚠ **顺序是"先换后截"，不许改成"先截后换"**（L2D-F2 实测驳回）：Java 的否定字符类把
     * 代理对当**一个码位**匹配，所以一次替换就把 2 个 UTF-16 单元缩成 1 个 `_` ⇒ 两序对
     * 含补充平面字符（emoji）的名字**输出不同**（实测 87/1600 例不一致，`PiqExportTest` 的
     * 反证哨兵钉住这条）。换过来的代价只是"超长名按全长多扫一遍"，而超长入口本身已被
     * [MAX_EXPORT_BYTES] 之外的页内自造名限制，属可读性优于微优化。
     */
    internal fun sanitizeName(raw: String?): String {
        val tail = raw.orEmpty().substringAfterLast('/').substringAfterLast('\\')
        val cleaned = tail.replace(SAFE_NAME, "_").take(MAX_NAME_CHARS).trim('.')
        return if (cleaned.isEmpty()) "periodiq-export" else cleaned
    }

    /**
     * 实际落盘名（R8 的 REG-08，两条产品裁定）：
     *  1. **含到时分**——页内给的只到日（`PeriodIQ-__-20261013.json`），同一天第二次导出就会撞名；
     *     主机在名后补 `-HHmm`（ASCII 消毒规则不变，只补不删）。
     *  2. **同名不覆盖**——已存在则依次试 `-2`、`-3`……（最多试 [MAX_COLLISION_TRIES] 次），
     *     回执与日志都报**最终落盘名**（`requested=` 保留原请求名，便于对照）。
     *
     * 可测判据（`S-AND-R9-EXPORT-NAME`）：连点两次导出 ⇒ `ls` 出两个文件、行数各自完整；
     * 时间来源是设备时钟（[now]，生产用系统时钟，单测注入固定时刻）。
     */
    internal fun allocateName(
        dir: File,
        sanitizedName: String,
        now: LocalDateTime = LocalDateTime.now(),
    ): String {
        val withTime = ensureTimePart(sanitizedName, now)
        if (!File(dir, withTime).exists()) return withTime
        val stem = withTime.substringBeforeLast('.')
        // `substringAfterLast('.', "")` 只会返回"最后一个点之后的严格后缀"或 `""`，
        // 所以这里只需判空：曾经那个 `it == withTime` 的或支恒假（台账 L3R93-REMAIN5 的 03）。
        val ext = withTime.substringAfterLast('.', missingDelimiterValue = "")
            .let { if (it.isEmpty()) "" else ".$it" }
        for (n in 2..MAX_COLLISION_TRIES) {
            val candidate = "$stem-$n$ext"
            if (!File(dir, candidate).exists()) {
                return candidate
            }
        }
        return "$stem-${System.currentTimeMillis()}$ext"
    }

    /**
     * 名字里还没有"时间尾段"就补一个 `-HHmm`。
     *
     * [TIME_TAIL] 认 4 位（`-HHmm`，本类自己生成的形态）与 6 位（`-HHmmss`）两种数字尾段。
     * ⚠ 已知边界（R9.1 C5 登记）：`report-0001.json` 这类"4 位编号"也会被认成时间尾段而不再补
     * —— 后果只是命名少一个时分段，同名二次导出仍由 [allocateName] 的 `-2`/`-3` 后缀兜住、**不会覆盖**，
     * 故按 P3 登记而非在此收紧（收紧成只认 6 位会让本类刚生成的 `-HHmm` 名字被再叠一段时间）。
     */
    private fun ensureTimePart(name: String, now: LocalDateTime): String {
        val dot = name.lastIndexOf('.')
        val hasExt = dot > 0 && dot < name.length - 1
        val stem = if (hasExt) name.substring(0, dot) else name
        val ext = if (hasExt) name.substring(dot) else ""
        // containsMatchIn：`$` 锚定在结尾，全串匹配（matches）只会认出"整个名字就是时间"这种荒谬形态
        if (TIME_TAIL.containsMatchIn(stem)) return name
        return stem + "-" + TIME_FORMAT.format(now) + ext
    }

    private fun result(ok: Boolean, name: String, bytes: Long, code: String, status: Int? = null): JSONObject =
        JSONObject().put("ok", ok).put("name", name).put("bytes", bytes).put("code", code).apply {
            if (status != null) put("status", status)
        }

    /**
     * 失败也给用户看得见的一句话（L2E-04）。日志里 `export|receipt|ok=false` 这一行是判据读数：
     * 改前只有 `export|rejected`（页内不读返回值 ⇒ 用户侧等于"点了没反应"），改后失败与成功一样留回执痕。
     */
    private fun postFailure(context: Context, name: String, code: String, reason: String) {
        val text = failureText(code, name)
        Log.i(TAG, "export|receipt|ok=false|code=$code|nameLen=${name.length}|reason=$reason|chars=${text.utf8ByteLength()}")
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        } else {
            Handler(Looper.getMainLooper()).post { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
        }
    }

    /** 失败文案：每个 `code` 都有一句人话，**不许**返回空串（空串 = 又把失败咽回去了）。 */
    internal fun failureText(code: String, name: String): String = when (code) {
        "TOO_LARGE" -> "这份导出超过 ${MAX_EXPORT_BYTES / (1024 * 1024)} MB 上限，没有落盘。"
        "EMPTY_PAYLOAD" -> "页内交来的内容是空的，$name 没有导出。"
        "BAD_BASE64" -> "页内交来的内容读不懂，$name 没有导出。"
        "WRITE_FAILED" -> "写入失败，$name 没有导出。换一个文件夹或再试一次。"
        else -> "导出失败（$code），$name 没有落盘。"
    }

    /**
     * 用户可见回执。桥方法跑在 WebView 的 JS 桥线程上，`Toast` 必须回主线程
     * （`Role/执行端.md` §5.3 的 FAIL 清单里"桥方法跑在 main 线程"是反面项，这里反向要求
     * UI 调用必须切回 main）。
     */
    private fun postReceipt(context: Context, name: String, bytes: Long) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            toast(context, name, bytes)
        } else {
            Handler(Looper.getMainLooper()).post { toast(context, name, bytes) }
        }
    }

    private fun toast(context: Context, name: String, bytes: Long) {
        val kb = if (bytes >= 1024) "${bytes / 1024} KB" else "$bytes B"
        Toast.makeText(context, "已导出 $name（$kb），在「Download」文件夹里", Toast.LENGTH_LONG).show()
    }
}
