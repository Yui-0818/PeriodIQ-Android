package io.github.yui0818.periodiq.domain.import

import org.json.JSONObject
import io.github.yui0818.periodiq.PiqConfig
import io.github.yui0818.periodiq.api.contract.ApiError
import io.github.yui0818.periodiq.api.contract.validationError

/**
 * `POST /api/v1/import` 请求体的入口闸门（**纯 JVM、不打日志**，所以可单测）。
 *
 * 三件事：
 *  1. **四档可读错误**（R8 的 REG-05）：① 空文件 ② 不是合法 JSON ③ 是 JSON 但最外层不是对象
 *     ④ 是对象但没有必要键。每档一句"哪里坏了"+ 一句下一步建议，**不得**再糊成一句。
 *  2. **解析之前的体积闸门**（R8 的 REG-02）：`MAX_IMPORT_BYTES` 按 UTF-8 **文本字节**判，
 *     超了直接 400 —— 页内 `JSON.parse` 是同步的（R8 实测 20 MB 阻塞 694 ms，且前端 60 s
 *     超时在桥路径上不可能触发），先拒比解析完再拒便宜一整条内存与时间。
 *  3. **BOM 剥离**（R8 的 REG-03）：带 UTF-8 BOM 的文件剥掉后照常解析。
 *     桌面 `json.loads` 对带 BOM 的串是**报错**的（实测），所以这一条是"安卓宽一档"，
 *     登记在 R9 报告 §6 与 `docs/契约/跨端契约.md` 的 R9 追加节。
 *     其余宽松形态（尾随垃圾/单引号/注释/十六进制/前导零/未加引号的键）桌面一律拒 ⇒
 *     由 [StrictJson] 拒掉，两端接受集对齐。
 */
object ImportBodyGate {

    /** UTF-8 BOM（U+FEFF）。用转义写：裸字符在编辑链路与控制字符扫描里都是个坑。 */
    private const val BOM = "\uFEFF"

    /** 体积上限（`PiqConfig.MAX_IMPORT_BYTES`）。 */
    private const val LIMIT = PiqConfig.MAX_IMPORT_BYTES

    /** 解析后的请求体；任何非法形态都收敛成 [ApiError]（状态码与 `code` 与桌面同族）。 */
    fun parse(raw: String?): JSONObject {
        val text = raw?.removePrefix(BOM)
        if (text.isNullOrBlank()) {
            throw validationError("body",
                "备份文件是空的（一个字符都没读到）。下一步：请用本应用或桌面端「导出备份」新生成一份文件，" +
                    "并在选择文件后看一眼体积，确认不是 0 KB。")
        }
        val bytes = utf8Bytes(text)
        if (bytes > LIMIT) {
            // 数字进文案用 Kotlin 模板（R9 实测教训：`"A" + "B".format(...)` 只格式化后半句，
            // 用户会看到原样的 `%.1f`；读数见 R9 报告 §3 的 REG-02 那一行）
            val mb = String.format(java.util.Locale.US, "%.1f", bytes.toDouble() / 1048576.0)
            val limitMb = String.format(java.util.Locale.US, "%.0f", LIMIT / 1048576.0)
            throw ApiError(
                400,
                "这份备份太大了（约 $mb MB，本机导入上限 $limitMb MB）。下一步：备份体积主要由每条记录" +
                    "的备注文字决定，先在桌面端清理超长备注或删掉最早的记录，再重新「导出备份」；" +
                    "正常使用的备份不会到这个量级（实测最大合法形态约 6.5 MB）。",
                "IMPORT_TOO_LARGE",
            )
        }
        val badAt = StrictJson.firstSyntaxError(text)
        if (badAt >= 0) {
            throw validationError("body",
                "这份文件不是合法的 JSON（读到第 ${badAt + 1} 个字符就断了，常见原因是多了逗号、" +
                    "用了单引号或注释）。下一步：重新导出一份原文件，不要用编辑器另存或手工改过。")
        }
        val kind = StrictJson.topLevelKind(text)
        if (kind != "对象") {
            throw validationError("body",
                "这份文件是 JSON，但最外层是${kind}；备份文件的最外层必须是一个对象（以 { 开头）。" +
                    "下一步：确认选的是「导出备份」得到的 .json，不是 CSV 或历史记录截图。")
        }
        val body = runCatching { JSONObject(text) }.getOrNull()
            ?: throw validationError("body",
                "这份 JSON 本机读不进去（文法检查通过，但解析器不接受它——例如裸的 NaN / Infinity）。" +
                    "下一步：用本应用或桌面端「导出备份」重新导出一份原文件。")
        if (!body.has("cycles") && !body.has("model_params")) {
            throw ApiError(
                400,
                "这份 JSON 里没有 cycles（周期记录）也没有 model_params（模型参数），看起来不是一份备份文件。" +
                    "下一步：确认选的是「导出备份」生成的文件，文件名形如 PeriodIQ-…-日期.json。",
                "EMPTY_IMPORT",
            )
        }
        return body
    }

    /**
     * 字符串按 UTF-8 编码后的字节数（不分配字节数组；REG-02 的"解析之前"长度闸门用它）。
     *
     * 合法代理对算 4 字节；**落单的代理半区本闸门按 3 计**——这是**故意估高**（闸门只多不少），**不是**编码器行为：
     * JDK 把落单代理编成 1 字节，`AssetRootHandler.kt:32-33` 那份按 1 计并由 `Utf8ByteLengthTest` 拿编码器当 oracle 验过。
     */
    fun utf8Bytes(text: String): Long {
        var total = 0L
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val high = c.code in 0xD800..0xDBFF
            val low = c.code in 0xDC00..0xDFFF
            val next = if (i + 1 < text.length) text[i + 1] else null
            when {
                high && next != null && next.code in 0xDC00..0xDFFF -> { total += 4; i += 2 }
                c.code < 0x80 -> { total += 1; i++ }
                c.code < 0x800 -> { total += 2; i++ }
                else -> { total += 3; i++ }
            }
        }
        return total
    }
}
