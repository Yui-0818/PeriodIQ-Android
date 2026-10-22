package io.github.yui0818.periodiq

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.UnsupportedTemporalTypeException
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 陈旧预测修正的审计时间戳哨兵（R9.1 第 2 轮 L2-A 的 F-1，P1）。
 *
 * 判据定义：`utcSecondsIso()` 产出的串必须
 * ① 不抛异常；② 能被 `DateTimeFormatter.ISO_OFFSET_DATE_TIME` **读回**；
 * ③ 带显式偏移（桌面 `datetime.now(timezone.utc).isoformat()` 也是 `+00:00` 形态）；
 * ④ 秒级精度（不含小数秒 —— 函数名与契约 §1 的"时间戳秒级"口径都这么要求）。
 *
 * 缺陷形态（基线 `a65d82e` 的 `PiqRouter.kt:1038-1039`）：拿 `LocalDateTime` 去
 * `format(ISO_OFFSET_DATE_TIME)`，而 `LocalDateTime` **没有偏移字段**
 * ⇒ 表达式每次必抛 `UnsupportedTemporalTypeException: Unsupported field: OffsetSeconds`；
 * 调用点在 `store.write { … }` 里、`createPrediction` 之前 ⇒ 事务回滚，
 * 修正永不落库，且 `handle()` 把异常收敛成 `500 INTERNAL_ERROR`。
 * 前两条用例先把这个事实钉住（第三、四条是修复后的终态判据）。
 */
class StaleCorrectionTimestampTest {

    /**
     * **对照用例**（L3-c 复核提醒后标注）：整条表达式只含 JDK 类，本仓库代码改坏它也不会红。
     * 它钉的是"基线为什么会抛"这一事实（换 JDK 才会红），产品行为判据是后面两条。
     */
    @Test
    fun `control baseline expression with LocalDateTime always throws - the P1 root cause`() {
        val boom = runCatching {
            LocalDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        }.exceptionOrNull()
        assertTrue(
            "基线表达式必须实测报 UnsupportedTemporalTypeException（否则这条 P1 不成立），实测=$boom",
            boom is UnsupportedTemporalTypeException,
        )
    }

    /** **对照用例**：同上，钉的是 JDK 抛错消息里那个字段名（`OffsetSeconds`），不是本仓库行为。 */
    @Test
    fun `control the throw is about the missing offset field not about the clock`() {
        val message = runCatching {
            LocalDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        }.exceptionOrNull()?.message.orEmpty()
        assertTrue("实测消息=$message", message.contains("OffsetSeconds"))
    }

    @Test
    fun `fixed helper returns a parseable offset timestamp with second precision`() {
        val text = utcSecondsIso()
        val parsed = runCatching { OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME) }
        assertTrue("产出串必须能被 ISO_OFFSET_DATE_TIME 读回，实测=$text", parsed.isSuccess)
        // 断言的是**串本身**的偏移写法：解析回来的 `ZoneOffset.toString()` 对 UTC 会印成 "Z"，
        // 拿它当"是不是 +00:00"的判据是测错了观测点（`Role/执行端.md` §5.4②）。
        assertTrue("偏移写法必须与桌面 isoformat 一致（+00:00 而不是 Z），实测=$text", text.endsWith("+00:00"))
        assertTrue("秒级精度：不得含小数秒，实测=$text", !text.contains('.'))
        assertTrue("形如 2026-10-14T05:56:01+00:00，实测=$text",
            Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\+00:00$").matches(text))
    }

    @Test
    fun `back-to-back calls each produce a valid timestamp of the same shape`() {
        val first = utcSecondsIso()
        val second = utcSecondsIso()
        // 用能真的变红的形态断言，而不是 `assertNotNull(parse(...))`（parse 失败是抛异常，那一条永远不红）
        val shape = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\+00:00$")
        assertTrue("两次调用都必须产出同一形态，实测=$first / $second",
            shape.matches(first) && shape.matches(second))
        // 同秒内两次调用可能相等 ⇒ **不断言不等**（那是一条会随机翻红的断言）；
        // 用例名也只承诺"每次都能产出合法值 + 形态一致"，不承诺"两次必不相同"。
        assertTrue("两次调用必须非递减（同一偏移下 ISO 秒级串按字典序即按时间序），实测 $first / $second", first <= second)
        // 阴性对照（**对照，不是产品行为判据**：它钉的是字符串 `<=` 的语义）：同一个 `<=` 必须能识破「时间倒退」，
        // 否则上面那条 `first <= second` 就可能是恒真的
        assertTrue("对照：倒序的一对必须判为递减",
            ("2026-01-02T00:00:00+00:00" <= "2026-01-01T00:00:00+00:00").not())
    }
}
