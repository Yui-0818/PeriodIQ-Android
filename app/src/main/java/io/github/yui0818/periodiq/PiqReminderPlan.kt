package io.github.yui0818.periodiq

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 三类提醒的排程**纯函数层**（R11 · 范围以 `R11 修复提示词2` §1 / §4 / §5 为准）：
 * 输入「五键设置 + 登录态 + 预计开始日 + 此刻」，输出「要挂的闹钟清单 + 每条为什么没挂」。
 *
 * 三类提醒各占一条独立的腿：
 *
 * | 提醒 | 触发日（本地 09:00） | 通知 id |
 * |---|---|---|
 * | 提前 N 天 | `预计开始日 − N 天` | 1 |
 * | 当日 | `预计开始日` | 2 |
 * | 逾期 | `预计开始日 + 1 天` 与 `+ 3 天`（**最多两条**） | 3 / 4 |
 *
 * 这一层刻意不碰 `Context` / `AlarmManager` / `NotificationManager` / 数据库，两个理由：
 *  1. "三条腿互不连带取消"这种规则必须在 JVM 上逐值钉住，设备侧只能看结果；
 *  2. 发通知那一步不许起 Python、不许碰 WebView（提示词 §2②）⇒ "什么时候发什么文案"
 *     必须在写盘那一刻就算清，落成可以被接收器直接复读的数据。
 *
 * **时刻口径**：触发时刻一律是**该时区当天的 09:00**（不是 UTC，也不是"提前 N×24 小时"），
 * 跨夏令时的 23 / 25 小时间隔由 [triggerAtLocalNine] 交给时区规则决定（哨兵见
 * `PiqReminderPlanTest` 的纽约用例）。
 */
object PiqReminderPlan {

    /** 三类提醒共用同一个通知渠道（提示词 §4）。 */
    const val CHANNEL_ID = "periodiq_reminder"

    /** 「提前 N 天」的通知 id（逐字：`1`）。 */
    const val NOTIFY_ID_ADVANCE = 1

    /** 「当日」的通知 id（逐字：`2`）。 */
    const val NOTIFY_ID_ON_DAY = 2

    /** 「逾期 +1 天」的通知 id（逐字：`3`）。 */
    const val NOTIFY_ID_OVERDUE_FIRST = 3

    /** 「逾期 +3 天」的通知 id（逐字：`4`）。 */
    const val NOTIFY_ID_OVERDUE_SECOND = 4

    /** 每条闹钟各自独立的 `PendingIntent` requestCode：id 与 requestCode 一一对应，互不顶替。 */
    const val REQUEST_CODE_ADVANCE = 11
    const val REQUEST_CODE_ON_DAY = 12
    const val REQUEST_CODE_OVERDUE_FIRST = 13
    const val REQUEST_CODE_OVERDUE_SECOND = 14

    /** 触发时刻：本地当日 09:00。 */
    const val TRIGGER_HOUR_OF_DAY = 9

    /** 下拉的合法候选（与快照 `js/app.js` 那五个 option 同源）；默认 3 天。 */
    val VALID_DAYS: List<Int> = listOf(1, 2, 3, 5, 7)
    const val DEFAULT_DAYS = 3

    /** 逾期的两个时刻（距预计开始日的天数），上限 = 表长；D+1 一条、D+3 一条，之后不再提醒。 */
    val OVERDUE_DAY_OFFSETS: List<Int> = listOf(1, 3)

    /** 逾期这一类最多挂几条（顾问口径：一天一条会变成骚扰，不许改成每天循环）。 */
    const val MAX_OVERDUE_ALARMS = 2

    /** `plan` 的判定结果里可读的"为什么没排"原因码（进 logcat，也进落盘计划的对照）。 */
    const val SKIP_MASTER_OFF = "master-off"
    const val SKIP_NO_SESSION = "no-session"
    const val SKIP_NO_PREDICTION = "no-prediction"
    const val SKIP_PREDICTED_PAST = "predicted-start-past"
    const val SKIP_ADVANCE_OFF = "advance-off"
    const val SKIP_ON_DAY_OFF = "on-day-off"
    const val SKIP_OVERDUE_OFF = "overdue-off"
    const val SKIP_ADVANCE_TIME_PASSED = "advance-time-passed"
    const val SKIP_ON_DAY_TIME_PASSED = "on-day-time-passed"
    const val SKIP_OVERDUE_TIME_PASSED = "overdue-time-passed"

    /** 调用点给的 N 不在合法集里 ⇒ 回落 [DEFAULT_DAYS]，并把这条写进 skipped。 */
    const val SKIP_DAYS_CLAMPED = "days-clamped"

    /** 通知正文里那个日期的形态：`11月6日`（月/日不补零）。 */
    private val DATE_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日")

    /** 五条设备级设置 + 登录态；`days` 允许是非法值，由 [plan] 回落（见 [normalizeDays]）。 */
    data class Options(
        val masterEnabled: Boolean,
        val advanceEnabled: Boolean,
        val onDayEnabled: Boolean,
        val overdueEnabled: Boolean,
        val days: Int,
        val loggedIn: Boolean,
    )

    /** 一条要挂的闹钟：通知 id、requestCode、本地触发日、时区、绝对时刻、逐字文案。 */
    data class Alarm(
        val notifyId: Int,
        val requestCode: Int,
        val triggerDate: LocalDate,
        val zoneId: String,
        val triggerEpochMillis: Long,
        val title: String,
        val body: String,
    )

    /** 排程结果：要挂的清单 + 每条"为什么没挂"的原因（去重后）。 */
    data class Plan(val alarms: List<Alarm>, val skipped: List<String>)

    /** 设置值 → N：只接受 [VALID_DAYS]，其余（`0`/`4`/`"abc"`/空串/`null`/负数）一律回落 [DEFAULT_DAYS]。 */
    fun normalizeDays(raw: String?): Int =
        raw?.toIntOrNull()?.takeIf { it in VALID_DAYS } ?: DEFAULT_DAYS

    /**
     * 排程主函数。
     *
     * 规则（提示词 §4）：
     *  - 总开关关 / 未登录 / 没有预测 ⇒ **三条腿全不排**（取消由调用点按 [Alarm] 清单做）；
     *  - `advanceEnabled` / `onDayEnabled` / `overdueEnabled` **互不影响**：关某一类只少某一类；
     *  - 「预计开始日已过」只否决**提前与当日**两条腿——逾期正是"开始日已过还没记录"那段；
     *  - 某条腿自己算出的时刻已过 ⇒ **只跳过那条**，其余照排；
     *  - 逾期只产出 [OVERDUE_DAY_OFFSETS] 里的两个时刻，条数封顶 [MAX_OVERDUE_ALARMS]。
     */
    fun plan(options: Options, predictedStart: LocalDate?, now: ZonedDateTime): Plan {
        if (!options.masterEnabled) return Plan(emptyList(), listOf(SKIP_MASTER_OFF))
        if (!options.loggedIn) return Plan(emptyList(), listOf(SKIP_NO_SESSION))
        if (predictedStart == null) return Plan(emptyList(), listOf(SKIP_NO_PREDICTION))

        val days = options.days.takeIf { it in VALID_DAYS } ?: DEFAULT_DAYS
        val clamped = if (days == options.days) emptyList() else listOf(SKIP_DAYS_CLAMPED)
        val zone = now.zone
        val nowMillis = now.toInstant().toEpochMilli()
        val startAlreadyPast = predictedStart.isBefore(now.toLocalDate())

        val alarms = mutableListOf<Alarm>()
        val skipped = mutableListOf<String>()

        if (!options.advanceEnabled) {
            skipped += SKIP_ADVANCE_OFF
        } else if (startAlreadyPast) {
            skipped += SKIP_PREDICTED_PAST
        } else {
            arm(
                alarms, skipped,
                date = predictedStart.minusDays(days.toLong()),
                zone = zone, nowMillis = nowMillis,
                notifyId = NOTIFY_ID_ADVANCE, requestCode = REQUEST_CODE_ADVANCE,
                title = advanceTitle(days), body = advanceBody(predictedStart),
                passedReason = SKIP_ADVANCE_TIME_PASSED,
            )
        }

        if (!options.onDayEnabled) {
            skipped += SKIP_ON_DAY_OFF
        } else if (startAlreadyPast) {
            skipped += SKIP_PREDICTED_PAST
        } else {
            arm(
                alarms, skipped,
                date = predictedStart,
                zone = zone, nowMillis = nowMillis,
                notifyId = NOTIFY_ID_ON_DAY, requestCode = REQUEST_CODE_ON_DAY,
                title = onDayTitle(), body = onDayBody(predictedStart),
                passedReason = SKIP_ON_DAY_TIME_PASSED,
            )
        }

        if (!options.overdueEnabled) {
            skipped += SKIP_OVERDUE_OFF
        } else {
            OVERDUE_DAY_OFFSETS.take(MAX_OVERDUE_ALARMS).forEachIndexed { index, offset ->
                arm(
                    alarms, skipped,
                    date = predictedStart.plusDays(offset.toLong()),
                    zone = zone, nowMillis = nowMillis,
                    notifyId = NOTIFY_ID_OVERDUE_FIRST + index,
                    requestCode = REQUEST_CODE_OVERDUE_FIRST + index,
                    title = overdueTitle(offset), body = overdueBody(),
                    passedReason = SKIP_OVERDUE_TIME_PASSED,
                )
            }
        }

        return Plan(alarms, (clamped + skipped).distinct())
    }

    /** 一条腿的落地：算出本地 09:00 的绝对时刻，没过期就收进清单，过期就记下原因。 */
    private fun arm(
        alarms: MutableList<Alarm>,
        skipped: MutableList<String>,
        date: LocalDate,
        zone: ZoneId,
        nowMillis: Long,
        notifyId: Int,
        requestCode: Int,
        title: String,
        body: String,
        passedReason: String,
    ) {
        val trigger = triggerAtLocalNine(date, zone)
        val epoch = trigger.toInstant().toEpochMilli()
        if (epoch > nowMillis) {
            alarms += Alarm(notifyId, requestCode, date, zone.id, epoch, title, body)
        } else {
            skipped += passedReason
        }
    }

    /** 「提前」的标题与正文（文案逐字，见修复提示词2 §5）。 */
    fun advanceTitle(days: Int): String = "距离预计经期还有 $days 天"

    fun advanceBody(predictedStart: LocalDate): String =
        "预计 ${predictedStart.format(DATE_LABEL)}开始 · 可在设置里调整提醒"

    /** 「当日」的标题与正文。 */
    fun onDayTitle(): String = "预计今天开始经期"

    fun onDayBody(predictedStart: LocalDate): String =
        "预计开始日 ${predictedStart.format(DATE_LABEL)} · 打开 App 记录实际情况"

    /** 「逾期」的标题与正文：K 是距预计开始日的天数（1 或 3）。 */
    fun overdueTitle(daysOverdue: Int): String = "预计经期已过 $daysOverdue 天"

    fun overdueBody(): String = "还没有记录这次的开始日 · 打开 App 记一下吧"

    /** 本地日期 + 当日 09:00 → 该时区里的绝对时刻（夏令时按时区规则走，不做固定偏移）。 */
    fun triggerAtLocalNine(date: LocalDate, zone: ZoneId): ZonedDateTime =
        date.atTime(TRIGGER_HOUR_OF_DAY, 0).atZone(zone)

    /** 全部已知通知 id（取消那一条腿时按 id `NotificationManager.cancel`，落盘复读时也按它过滤）。 */
    val ALL_NOTIFY_IDS: List<Int> = listOf(
        NOTIFY_ID_ADVANCE,
        NOTIFY_ID_ON_DAY,
        NOTIFY_ID_OVERDUE_FIRST,
        NOTIFY_ID_OVERDUE_SECOND,
    )
}
