package io.github.yui0818.periodiq

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Python 语义的数值与时间工具（R3 F1/F2）。
 *
 * 存在理由：Kotlin 移植的数据层要复刻桌面 `crud.py` / `routes/analysis.py` 里**在 Python 侧**
 * 完成的取整与统计口径。JVM 的默认舍入与 Python 不同，直接用会造出"两端同输入不同输出"：
 * - `round(x, n)`（Python）= 对**二进制精确值**做四舍六入五取偶；`Math.round`（Java）= 十进制四舍五入
 *   ⇒ `roundTo(2.675, 2)` 必须得 `2.67`（Python 实测值），Java 朴素写法得 `2.68`；
 * - `statistics.median` 偶数长度取**中间两数的算术均值**，奇数长度取中间元素；
 * - SQLite `CURRENT_TIMESTAMP` 是 **UTC** 秒级串 `YYYY-MM-DD HH:MM:SS`。
 *
 * 每条都有 JVM 单测对桌面 Python 实测值（`PyMathCompatTest`）。
 */
object PyMath {

    private val TS_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private val BASIC_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE
    private val WEEK_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_WEEK_DATE

    /** 入站日期的三种字面形态（`YYYY-MM-DD` / `YYYYMMDD` / `YYYY-Www-d`，W 必须大写）。 */
    private val ISO_DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    private val BASIC_DATE = Regex("^\\d{8}$")
    private val WEEK_DATE = Regex("^\\d{4}-W\\d{2}-[1-7]$")

    /** Python `datetime.date` 的域下界是 `0001-01-01`（`date.min`）；java.time 收 year 0 ⇒ 必须显式挡。 */
    private const val MIN_PYTHON_YEAR = 1

    /** Python `%g` 的默认有效位数（`'%.6g'`）。 */
    private const val GENERAL_SIG_DIGITS = 6

    /** Python `round(value, digits)`：二进制精确值 + HALF_EVEN。 */
    fun roundTo(value: Double, digits: Int): Double =
        BigDecimal(value).setScale(digits, RoundingMode.HALF_EVEN).toDouble()

    /**
     * Python `f"{value:.{digits}f}"`：对**二进制精确值**做 HALF_EVEN，输出恒定长小数。
     *
     * 为什么不用 `String.format("%.1f", …)`：JVM 默认 HALF_UP，`0.625` 会被写成 `0.63`
     * 而 Python 写 `0.62` —— 透视镜的权重说明文案（`51.2%` / `48.8%`）是**响应字段**，
     * 两端必须逐字一致（契约 §2.0）。
     */
    fun fixedFormat(value: Double, digits: Int): String =
        BigDecimal(value).setScale(digits, RoundingMode.HALF_EVEN).toPlainString()

    /**
     * Python `f"{value:g}"`：[GENERAL_SIG_DIGITS] 位有效数字、HALF_EVEN、去尾零；
     * 指数 ≤ −5 或 ≥ 位数时转科学计数（`1e-05` / `1e+06`，指数补齐两位）。
     * 透视镜用它输出 `n_eff` 与衰减常数 `k`（`n_eff=5.26，k=5`）。
     */
    fun generalFormat(value: Double): String {
        val rounded = BigDecimal(value)
            .round(MathContext(GENERAL_SIG_DIGITS, RoundingMode.HALF_EVEN))
            .stripTrailingZeros()
        if (rounded.signum() == 0) return "0"
        val exponent = rounded.precision() - rounded.scale() - 1
        if (exponent <= -5 || exponent >= GENERAL_SIG_DIGITS) {
            val sign = if (exponent < 0) "-" else "+"
            return rounded.movePointLeft(exponent).toPlainString() +
                "e" + sign + kotlin.math.abs(exponent).toString().padStart(2, '0')
        }
        return rounded.toPlainString()
    }

    /** Python `round(value)`（0 位，仍为五取偶）。 */
    fun roundHalfEven(value: Double): Long =
        BigDecimal(value).setScale(0, RoundingMode.HALF_EVEN).longValueExact()

    /** Python `statistics.median`（空序列按调用方守卫，这里返回 NaN 并由调用方保证非空）。 */
    fun median(values: List<Double>): Double {
        if (values.isEmpty()) return Double.NaN
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    /** SQLite `CURRENT_TIMESTAMP` 同格式的"此刻"（UTC，秒级）。 */
    fun nowSqliteUtc(): String = LocalDateTime.now(ZoneOffset.UTC).format(TS_FORMAT)

    /** `datetime.now(timezone.utc) + timedelta(days=n)` 的 SQLite 串形态。 */
    fun utcPlusDays(days: Long): String =
        LocalDateTime.now(ZoneOffset.UTC).plusDays(days).format(TS_FORMAT)

    /** `(a - b).days`（日历日差，与 Python `date` 相减同口径）。 */
    fun daysBetween(b: LocalDate, a: LocalDate): Long = java.time.temporal.ChronoUnit.DAYS.between(b, a)

    /**
     * **库内**日期/时间戳列的宽松读取：取前 10 个字符按 ISO 解析。
     *
     * 之所以要截断，是为本表的 `TIMESTAMP` 列服务（`created_at` 形如 `YYYY-MM-DD HH:MM:SS`）。
     * ⚠ **不得**用于入站校验：`take(10)` 会把 `2026-09-02ABC`、`2026-09-02T10:30` 这类
     * 桌面必定拒绝的形态折成合法日期写进库。入站一律用
     * [parseDateStrictIso]（POST/PUT）或 [parseDateFromIsoFormat]（导入）。
     */
    fun parseIsoDate(raw: String?): LocalDate? {
        if (raw.isNullOrEmpty()) return null
        return runCatching { LocalDate.parse(raw.take(10), DATE_FORMAT) }.getOrNull()
    }

    /**
     * 入站日期的严格形态（桌面 pydantic `date` 字段，实测 pydantic 2.13.4）：
     * 只接受 `YYYY-MM-DD`、且年份必须落在 Python `date` 的域 `0001-01-01 .. 9999-12-31`
     * （`0000-01-01` 桌面拒、java.time 收 ⇒ 必须显式拒，见 R8 的 F-01）。
     */
    fun parseDateStrictIso(raw: Any?): LocalDate? =
        (raw as? String)?.let { runCatching { LocalDate.parse(it, DATE_FORMAT) }.getOrNull() }
            ?.takeIf { it.year >= MIN_PYTHON_YEAR }

    /**
     * 入站日期的导入形态（桌面 `settings._parse_iso_date` → `date.fromisoformat`，实测 3.11.15）。
     *
     * 接受集 = `YYYY-MM-DD` ∪ `YYYYMMDD` ∪ `YYYY-Www-d`（**大写 W**，小写 `w` 桌面拒），
     * 且年份必须 ≥ 1。22 种形态的双解释器对照读数见 R8 报告 §3 与
     * `.dsh/r8_probe/logs/date_parity_*.txt`；`2025-W53-1` / `2025-W02-8` / `10000-01-01`
     * 两侧同样拒绝，不需要额外规则。
     */
    fun parseDateFromIsoFormat(raw: Any?): LocalDate? {
        val text = raw as? String ?: return null
        if (WEEK_DATE.matches(text)) {
            return runCatching { LocalDate.parse(text, WEEK_DATE_FORMAT) }.getOrNull()?.takeIf { it.year >= MIN_PYTHON_YEAR }
        }
        if (BASIC_DATE.matches(text)) {
            return runCatching { LocalDate.parse(text, BASIC_DATE_FORMAT) }.getOrNull()?.takeIf { it.year >= MIN_PYTHON_YEAR }
        }
        if (!ISO_DATE.matches(text)) return null
        return runCatching { LocalDate.parse(text, DATE_FORMAT) }.getOrNull()?.takeIf { it.year >= MIN_PYTHON_YEAR }
    }

    /** Python `int(round(x))` 的组合口径（fertile-window 的历史经期中位数用它）。 */
    fun intRound(value: Double): Int = roundHalfEven(value).toInt()
}

/** `date.isoformat()`（顶层扩展：object 内的扩展函数只在 object 作用域可见）。 */
fun LocalDate.toIso(): String = format(DateTimeFormatter.ISO_LOCAL_DATE)
