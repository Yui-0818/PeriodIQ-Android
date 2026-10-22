package io.github.yui0818.periodiq.domain.import

import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject
import io.github.yui0818.periodiq.PiqConfig
import io.github.yui0818.periodiq.PyMath
import io.github.yui0818.periodiq.api.contract.ApiError
import io.github.yui0818.periodiq.api.contract.validationError
import io.github.yui0818.periodiq.doubleOrNull
import io.github.yui0818.periodiq.textOrNull
import io.github.yui0818.periodiq.toIso

/**
 * 导入异常门禁 G1-G5（桌面 `app/services/gate.py::evaluate_gate` 的移植，阈值取
 * [PiqConfig] 同源常量；判据不得放宽）。
 *
 * 与桌面同口径的三处细节：① 派生周期长度与 G4 相邻间隔必须按**日历升序**算，
 * 不能用列表下标（导出接口的 cycles 是倒序）；② 库内无历史时 G3/G4 一律不适用；
 * ③ 输出顺序 = 输入序，同一条记录内 G1→G5。
 */
object ImportGate {

    /**
     * 导入体里**日期字段的 JSON 键 → 文案标签**对照表（R9 的 R8-B1：生产调用点必须从这里取，
     * 不再各写一遍字面量）。R8 把两者合成一个参数时，`row.has("cycles[].period_start")` 恒假
     * ⇒ 任何合法备份都吃 400；而当时的单测给的是真实键，所以 80 条全绿也没接住（L2 第 1 轮 F-1）。
     * 现在：调用点用常量，哨兵验"标签的最后一段必须等于键"，写反就红。
     */
    val IMPORT_DATE_FIELDS = listOf(
        ImportDateField("period_start", "cycles[].period_start"),
        ImportDateField("period_end", "cycles[].period_end"),
        ImportDateField("predicted_date", "predictions[].predicted_date"),
        ImportDateField("predicted_start_upper", "predictions[].predicted_start_upper"),
        ImportDateField("predicted_start_lower", "predictions[].predicted_start_lower"),
        ImportDateField("actual_date", "predictions[].actual_date"),
    )

    /**
     * 一个导入日期字段：`key` 是备份里的 JSON 键（**唯一取值依据**），`label` 只进错误文案。
     * 取值方法挂在字段上 ⇒ 调用点写不出"把标签当键"这种形态（R8 的 R8-B1 就是这么坏的）。
     */
    data class ImportDateField(val key: String, val label: String) {
        /** 必填：键缺失 / null / 空串 / 垃圾形态都抛 400 `INVALID_IMPORT`，文案用本字段的 label。 */
        fun require(row: JSONObject): java.time.LocalDate = ImportGate.requireImportDate(row, key, label)

        /** 选填：只有键缺失或显式 JSON null 合法（返回 null）。 */
        fun optional(row: JSONObject): java.time.LocalDate? = ImportGate.optionalImportDate(row, key, label)
    }

    /** 表里每个字段的具名入口（调用点用名字，不用下标）。 */
    val CYCLE_START = IMPORT_DATE_FIELDS[0]
    val CYCLE_END = IMPORT_DATE_FIELDS[1]
    val PRED_DATE = IMPORT_DATE_FIELDS[2]
    val PRED_UPPER = IMPORT_DATE_FIELDS[3]
    val PRED_LOWER = IMPORT_DATE_FIELDS[4]
    val PRED_ACTUAL = IMPORT_DATE_FIELDS[5]


    private const val G1 = "G1"
    private const val G2 = "G2"
    private const val G3 = "G3"
    private const val G4 = "G4"
    private const val G5 = "G5"

    private val EXPECTED = mapOf(
        G1 to "${PiqConfig.GATE_CYCLE_MIN_DAYS}–${PiqConfig.GATE_CYCLE_MAX_DAYS} 天",
        G2 to "不晚于今天",
        G3 to "偏离历史中位数不超过 ${PiqConfig.GATE_HISTORY_DEVIATION_DAYS.toInt()} 天",
        G4 to "相邻开始日间隔不超过 ${PiqConfig.GATE_GAP_MAX_DAYS.toInt()} 天",
        G5 to "${PiqConfig.GATE_PERIOD_MIN_DAYS}–${PiqConfig.GATE_PERIOD_MAX_DAYS} 天",
    )

    /**
     * 桌面 `f"{cycle_length:.0f}"` 的口径：Python 的 format 用**四舍六入五取偶**，
     * 而 `${x.toInt()}` 是**向零截断**（R9.1 实测：6.7 桌面印 `7`、截断印 `6`；6.5 桌面印 `6`、截断印 `6`）
     * ⇒ 异常文案里的天数必须走这一条，不能直接 `.toInt()`。
     */
    internal fun pyDays(value: Double): String =
        java.math.BigDecimal.valueOf(value).setScale(0, java.math.RoundingMode.HALF_EVEN).toPlainString()

    /**
     * 桌面 `f"{x:.1f}"` 的口径：一位小数，且**不跟设备 locale** ——
     * `"...%.1f...".format(...)` 用的是 `Locale.getDefault()`，在 `de_DE`/`fr_FR` 下会印成 `22,3`
     * （同仓库 `ImportBodyGate.kt:40` 与下面那行都已锁 `Locale.US` ⇒ 这类调用点共两处，无漏网）。
     */
    internal fun pyDays1(value: Double): String = String.format(java.util.Locale.US, "%.1f", value)

    fun evaluate(cycles: List<JSONObject>, historyCycleLengths: List<Double>, today: LocalDate): JSONArray {
        val anomalies = JSONArray()
        val historyMedian = if (historyCycleLengths.isEmpty()) null else PyMath.median(historyCycleLengths)
        val hasHistory = historyMedian != null

        data class Entry(val idx: Int, val start: LocalDate?, val end: LocalDate?)

        val entries = cycles.mapIndexed { idx, c ->
            Entry(idx, PyMath.parseIsoDate(c.textOrNull("period_start")), PyMath.parseIsoDate(c.textOrNull("period_end")))
        }
        val chrono = entries.filter { it.start != null }.sortedBy { it.start }
        val derivedLength = mutableMapOf<Int, Double>()
        val chronoGap = mutableMapOf<Int, Pair<Long, LocalDate>>()
        for (k in 1 until chrono.size) {
            val gap = PyMath.daysBetween(chrono[k - 1].start!!, chrono[k].start!!)
            derivedLength[chrono[k].idx] = gap.toDouble()
            chronoGap[chrono[k].idx] = gap to chrono[k - 1].start!!
        }

        for (entry in entries) {
            val row = cycles[entry.idx]
            val providedLength = row.doubleOrNull("cycle_length")
            val providedDays = row.doubleOrNull("period_days")
            val cycleLength = providedLength ?: derivedLength[entry.idx]
            val periodDays = when {
                providedDays != null -> providedDays.toDouble()
                entry.start != null && entry.end != null -> (PyMath.daysBetween(entry.start, entry.end) + 1).toDouble()
                else -> null
            }
            val label = entry.start?.toIso() ?: "#${entry.idx + 1}"

            if (cycleLength != null &&
                (cycleLength < PiqConfig.GATE_CYCLE_MIN_DAYS || cycleLength > PiqConfig.GATE_CYCLE_MAX_DAYS)
            ) {
                anomalies.put(anomaly(G1, cycleLength,
                    "记录 $label 的周期长度 ${pyDays(cycleLength)} 天超出常见范围" +
                        "（${PiqConfig.GATE_CYCLE_MIN_DAYS}–${PiqConfig.GATE_CYCLE_MAX_DAYS} 天）", "cycle_length"))
            }
            listOf(("开始日期" to entry.start), ("结束日期" to entry.end)).forEach { (labelDate, d) ->
                if (d != null && d > today) {
                    anomalies.put(anomaly(G2, d.toIso(),
                        "记录 $label 的$labelDate ${d.toIso()} 晚于今天（${today.toIso()}）",
                        if (labelDate == "开始日期") "period_start" else "period_end"))
                }
            }
            if (hasHistory && cycleLength != null) {
                val deviation = kotlin.math.abs(cycleLength - historyMedian!!)
                if (deviation > PiqConfig.GATE_HISTORY_DEVIATION_DAYS) {
                    anomalies.put(anomaly(G3, cycleLength,
                        "记录 $label 的周期长度 ${pyDays(cycleLength)} 天与库内历史中位数 " +
                            "${pyDays1(historyMedian)} 天偏离 ${pyDays1(deviation)} 天" +
                            "（阈值 ${PiqConfig.GATE_HISTORY_DEVIATION_DAYS.toInt()} 天）",
                        "cycle_length"))
                }
            }
            chronoGap[entry.idx]?.let { (gap, prevStart) ->
                if (hasHistory && gap > PiqConfig.GATE_GAP_MAX_DAYS) {
                    anomalies.put(anomaly(G4, gap,
                        "记录 $label 与上一条开始日 ${prevStart.toIso()} 间隔 $gap 天" +
                            "（阈值 ${PiqConfig.GATE_GAP_MAX_DAYS} 天）", "period_start_gap"))
                }
            }
            if (periodDays != null &&
                (periodDays < PiqConfig.GATE_PERIOD_MIN_DAYS || periodDays > PiqConfig.GATE_PERIOD_MAX_DAYS)
            ) {
                anomalies.put(anomaly(G5, periodDays,
                    "记录 $label 的经期时长 ${pyDays(periodDays)} 天超出常见范围" +
                        "（${PiqConfig.GATE_PERIOD_MIN_DAYS}–${PiqConfig.GATE_PERIOD_MAX_DAYS} 天）", "period_days"))
            }
        }
        return anomalies
    }

    private fun anomaly(code: String, value: Any?, message: String, field: String): JSONObject = JSONObject()
        .put("code", code)
        .put("field", field)
        .put("value", JSONObject.wrap(value))
        .put("expected", EXPECTED[code])
        .put("message", message)

    /**
     * 导入请求的**容器形态**校验（桌面由 pydantic `ImportRequest` 承担：
     * `cycles: list[dict] = []`、`predictions: list[dict] = []`、`model_params: Optional[dict] = None`）。
     *
     * 为什么必须在闸门**之前**做：调用方原本用 `body.optJSONArray("cycles") ?: JSONArray()` 取容器，
     * 于是 `cycles` 给字符串/对象/数字时被**静默折成空数组**（实测 pydantic 对
     * `'abc' / {} / 5 / None` 一律 ValidationError ⇒ 422）。折成空数组本身还不算最坏——
     * 只要同一份体里 `model_params` 非空就能过空导入闸门，然后走"清空重建"，
     * 把用户已有的全部周期与预测删光并回 200 "成功导入 0 条"（R8 的 F-14，P0）。
     */
    fun requirePayloadShapes(body: JSONObject) {
        requireArrayOrAbsent(body, "cycles")
        requireArrayOrAbsent(body, "predictions")
        requireObjectOrAbsent(body, "model_params")
        requireObjectOrAbsent(body, "app_settings")
    }

    /** 桌面 `cycles: list[dict] = []` / `predictions: list[dict] = []`：缺席合法，给了就必须是数组。 */
    private fun requireArrayOrAbsent(body: JSONObject, key: String) {
        if (!body.has(key) || body.isNull(key)) return
        if (body.opt(key) !is JSONArray) throw validationError(key, "should be a list")
    }

    /** 桌面 `model_params: Optional[dict] = None` / `app_settings: Optional[dict] = None`。 */
    private fun requireObjectOrAbsent(body: JSONObject, key: String) {
        if (!body.has(key) || body.isNull(key)) return
        if (body.opt(key) !is JSONObject) throw validationError(key, "should be a dict")
    }

    /**
     * `predictions[].confidence` 的取值口径（桌面 `isinstance(confidence, (int,float)) and
     * not isinstance(confidence, bool)`）：**只有 JSON 数字**算有值。
     *
     * 数字字符串 `"0.5"` 在桌面是"无值" ⇒ 400；此前安卓走 `doubleOrNull`，它会把字符串转成数字，
     * 于是脏值随清空重建入库（R8 的 F-16）。
     */
    fun confidenceValue(row: JSONObject): Double? = (row.opt("confidence") as? Number)?.toDouble()

    /**
     * 导入体里是否带 **U+FFFD**（替换字符）。它是"字节流按 UTF-8 解码失败"的唯一可见痕迹：
     * 页内用 `FileReader.readAsText(file,'utf-8')` 读文件是**非致命**的，GBK/ANSI 文件会被
     * 静默替换成 U+FFFD 后照常解析，导入还回 200"已导入 1 条"——中文备注在库里已经是乱码。
     *
     * 实现取 `toString()` 全量扫描（一次序列化）而不是递归走树：导入体积上限是
     * `MAX_IMPORT_CYCLES=1000` / `MAX_IMPORT_PREDICTIONS=2000`，一次额外字符串分配的代价可接受，
     * 换来"任何层级的损坏都跑得掉"这条更强的一致性。
     */
    fun hasReplacementCharacter(body: JSONObject): Boolean = body.toString().contains(REPLACEMENT_CHAR)

    /**
     * 空导入闸门（桌面 `settings.import_data` 的 `if not body.cycles and not body.model_params`）。
     *
     * Python 真值判断把 `{}` 与 `null` 同样算"无内容"；而安卓的导入是**清空重建**型单事务操作
     * （导入编排先 `deleteUserCycles`/`deleteUserPredictions` 再插入），
     * 所以闸门漏判一次就会把用户已有的全部周期与历史预测抹掉 —— 这一条按 P0 对待。
     */
    fun hasImportablePayload(body: JSONObject): Boolean =
        (body.optJSONArray("cycles")?.length() ?: 0) > 0 || isNonEmptyObject(body.opt("model_params"))

    /**
     * `predictions[].model_version` 的取值口径（桌面 `str(p.get("model_version", "unknown"))`）。
     *
     * 只有**键缺失**才回退 `"unknown"`；键存在但显式为 `null` 时桌面得到字符串 `"None"`，
     * 空串得到 `""`，数字得到 `"5"` —— 三者都不在白名单里 ⇒ 一律 400（"宁严不宽"）。
     * 安卓若把这些形态统统折成 `"unknown"`，脏值就会随清空重建写库并流向透视镜 DOM。
     */
    fun normalizeModelVersion(row: JSONObject): String = when {
        !row.has("model_version") -> "unknown"
        row.isNull("model_version") -> "None"
        else -> row.opt("model_version").toString()
    }

    /**
     * 必填导入日期（桌面 `settings._parse_iso_date(..., required=True)`）：
     * 键缺失 / JSON null / 空串 / 尾随垃圾 / 非字符串一律 400 `INVALID_IMPORT`。
     *
     * ⚠ [key] 与 [label] 必须是**两个参数**：R8 把它们合成过一个参数，于是
     * `row.has("cycles[].period_start")` 恒假 ⇒ 任何合法备份都吃 400、导入链路整体不可用
     * （R9 的 R8-B1，运行时读数见 R9 报告 §3）。
     */
    fun requireImportDate(row: JSONObject, key: String, label: String): LocalDate =
        isoOrBasicDateOrNull(row.opt(key)) ?: throw invalidImportDate(label)

    /**
     * 选填导入日期（桌面 `settings._parse_iso_date(..., required=False)`）：
     * **只有键缺失或显式 JSON null** 才合法（返回 null）；空串与垃圾形态仍须 400，
     * 否则它们会被静默写成 NULL，把"文件里写了空日期"伪装成"这一列本来就没填"。
     */
    fun optionalImportDate(row: JSONObject, key: String, label: String): LocalDate? {
        if (!row.has(key) || row.isNull(key)) return null
        return isoOrBasicDateOrNull(row.opt(key)) ?: throw invalidImportDate(label)
    }

    /**
     * 与桌面 `datetime.date.fromisoformat` 的接受集**逐形一致**（15 种形态的双解释器对照表见
     * R8 报告 §3）：单一实现是 [PyMath.parseDateFromIsoFormat]，这里不再重写口径。
     */
    private fun isoOrBasicDateOrNull(raw: Any?): LocalDate? = PyMath.parseDateFromIsoFormat(raw)

    /** `model_params` 的 Python 真值等价物：只有**非空对象**才算"有内容"（null / 缺失 / `{}` 都为假）。 */
    private fun isNonEmptyObject(value: Any?): Boolean = value is JSONObject && value.length() > 0

    /** U+FFFD：字节流按 UTF-8 解码失败后留下的替换字符。**这里就是裸字符**——本轮实测把它换成 \uFFFD 的转义写法会让 classes3.dex 的 CRC 改变（331 条目里 1 条不同），而本轮已经没有可信的重建窗口 ⇒ 写法不动，只把注释改成与实现一致；要真换成转义写法就进 R9.4 那一批（与剩余 5 条同批重建）。 */
    private const val REPLACEMENT_CHAR = '�'

    private fun invalidImportDate(field: String): ApiError =
        ApiError(400, "$field 缺失或不是合法的 ISO 日期（YYYY-MM-DD）", "INVALID_IMPORT")
}
