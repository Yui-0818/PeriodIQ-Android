package io.github.yui0818.periodiq

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 三类提醒（提前 / 当日 / 逾期）的排程哨兵（R11，范围以 `R11 修复提示词2` §1 与 §6 为准）。
 *
 * **这一层要钉住的四件事**：
 *  1. 三条腿各自独立产出，**互不吞掉、互不连带取消**（关掉一类只少那一类）；
 *  2. 逾期只有 `D+1` 与 `D+3` 两个时刻，**最多两条**（不是每天循环）；
 *  3. `N` 只接受 `1/2/3/5/7`，其余回落 `3`；
 *  4. 触发时刻是**该时区当天的 09:00**（跨夏令时不是"加 24 小时"）。
 *
 * **判红靠什么**（`Role/执行端.md` §5.9 第 13 条：恒等型断言必须自曝）：
 *  - 变异 A：把"互不影响"写成"任一开关关就全取消" ⇒ 第 4 / 5 / 6 号用例报红；
 *  - 变异 B：把逾期 offsets 改成 `1..7` 每天一条 ⇒ 第 3 / 11 号用例报红
 *    （第 11 号那条"不超过两条"在**当前常量**下恒等成立，它自己不设防；
 *    判红靠的是第 3 号对 `[D+1, D+3]` 两个日期的逐值断言，两条配对使用才有效）；
 *  - 变异 C：把"已过就跳过"删掉 ⇒ 第 8 / 9 号用例报红。
 */
class PiqReminderPlanTest {

    private val shanghai: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 全未来夹具：预计开始日 2026-11-06，此刻 2026-10-19 上午 8 点（三类都还没到点）。 */
    private val futureStart: LocalDate = LocalDate.of(2026, 11, 6)
    private val beforeNine: ZonedDateTime = ZonedDateTime.of(2026, 10, 19, 8, 0, 0, 0, shanghai)

    private fun options(
        master: Boolean = true,
        advance: Boolean = true,
        onDay: Boolean = true,
        overdue: Boolean = true,
        days: Int = 3,
        loggedIn: Boolean = true,
    ) = PiqReminderPlan.Options(
        masterEnabled = master,
        advanceEnabled = advance,
        onDayEnabled = onDay,
        overdueEnabled = overdue,
        days = days,
        loggedIn = loggedIn,
    )

    private fun ids(plan: PiqReminderPlan.Plan): List<Int> = plan.alarms.map { it.notifyId }.sorted()

    private fun run(options: PiqReminderPlan.Options, start: LocalDate?, now: ZonedDateTime) =
        PiqReminderPlan.plan(options, start, now)

    // ---------------------------------------------------------------- 1. 时刻口径

    @Test
    fun `advance alarm is predicted start minus N days at local nine`() {
        val plan = run(options(advance = true, onDay = false, overdue = false, days = 3), futureStart, beforeNine)
        assertEquals(listOf(PiqReminderPlan.NOTIFY_ID_ADVANCE), ids(plan))
        val alarm = plan.alarms.single()
        assertEquals(LocalDate.of(2026, 11, 3), alarm.triggerDate)
        assertEquals(PiqReminderPlan.REQUEST_CODE_ADVANCE, alarm.requestCode)
        assertEquals(
            ZonedDateTime.of(2026, 11, 3, 9, 0, 0, 0, shanghai).toInstant().toEpochMilli(),
            alarm.triggerEpochMillis,
        )
    }

    @Test
    fun `on day alarm is the predicted start itself at local nine`() {
        val plan = run(options(advance = false, onDay = true, overdue = false), futureStart, beforeNine)
        val alarm = plan.alarms.single()
        assertEquals(PiqReminderPlan.NOTIFY_ID_ON_DAY, alarm.notifyId)
        assertEquals(futureStart, alarm.triggerDate)
        assertEquals(
            ZonedDateTime.of(2026, 11, 6, 9, 0, 0, 0, shanghai).toInstant().toEpochMilli(),
            alarm.triggerEpochMillis,
        )
    }

    /**
     * 逾期的**逐值**断言：日期必须是 `D+1` 与 `D+3` 这两个点，id 分别是 3 与 4。
     * 变异 B（"逾期改成每天一条"）就是被这一条判红的——它同时钉住了日期与条数。
     */
    @Test
    fun `overdue alarms are exactly start plus one and start plus three`() {
        val plan = run(options(advance = false, onDay = false, overdue = true), futureStart, beforeNine)
        assertEquals(
            listOf(PiqReminderPlan.NOTIFY_ID_OVERDUE_FIRST, PiqReminderPlan.NOTIFY_ID_OVERDUE_SECOND),
            ids(plan),
        )
        assertEquals(listOf(LocalDate.of(2026, 11, 7), LocalDate.of(2026, 11, 9)), plan.alarms.map { it.triggerDate })
        assertEquals(listOf("1", "3"), plan.alarms.map { it.title.substringAfter("已过 ").substringBefore(" 天") })
        assertEquals(
            listOf(PiqReminderPlan.REQUEST_CODE_OVERDUE_FIRST, PiqReminderPlan.REQUEST_CODE_OVERDUE_SECOND),
            plan.alarms.map { it.requestCode },
        )
    }

    @Test
    fun `the three kinds use four distinct request codes so pending intents do not replace each other`() {
        val plan = run(options(), futureStart, beforeNine)
        assertEquals(4, plan.alarms.size)
        assertEquals(4, plan.alarms.map { it.requestCode }.distinct().size)
        assertEquals(4, plan.alarms.map { it.notifyId }.distinct().size)
    }

    // ---------------------------------------------------------------- 2. 2^3 开关矩阵

    /**
     * 8 组开关组合，全部时刻未到：产出的通知 id 集合必须**正好**等于打开的那几类
     * （提前→1，当日→2，逾期→3,4）。这一条同时否掉"任一关就全取消"（变异 A）。
     */
    @Test
    fun `switch matrix yields exactly the enabled kinds`() {
        val expected = mapOf(
            Triple(false, false, false) to emptyList<Int>(),
            Triple(true, false, false) to listOf(PiqReminderPlan.NOTIFY_ID_ADVANCE),
            Triple(false, true, false) to listOf(PiqReminderPlan.NOTIFY_ID_ON_DAY),
            Triple(false, false, true) to
                listOf(PiqReminderPlan.NOTIFY_ID_OVERDUE_FIRST, PiqReminderPlan.NOTIFY_ID_OVERDUE_SECOND),
            Triple(true, true, false) to listOf(PiqReminderPlan.NOTIFY_ID_ADVANCE, PiqReminderPlan.NOTIFY_ID_ON_DAY),
            Triple(true, false, true) to
                listOf(
                    PiqReminderPlan.NOTIFY_ID_ADVANCE,
                    PiqReminderPlan.NOTIFY_ID_OVERDUE_FIRST,
                    PiqReminderPlan.NOTIFY_ID_OVERDUE_SECOND,
                ),
            Triple(false, true, true) to
                listOf(
                    PiqReminderPlan.NOTIFY_ID_ON_DAY,
                    PiqReminderPlan.NOTIFY_ID_OVERDUE_FIRST,
                    PiqReminderPlan.NOTIFY_ID_OVERDUE_SECOND,
                ),
            Triple(true, true, true) to listOf(1, 2, 3, 4),
        )
        expected.forEach { (switches, want) ->
            val (advance, onDay, overdue) = switches
            val got = ids(run(options(advance = advance, onDay = onDay, overdue = overdue), futureStart, beforeNine))
            assertEquals("advance=$advance onDay=$onDay overdue=$overdue", want.sorted(), got)
        }
    }

    /** 反过来看同一件事：关掉任何一类，另外两类必须**原样还在**（提示词 §4"三者互不影响"）。 */
    @Test
    fun `turning one kind off leaves the other two untouched`() {
        val all = run(options(), futureStart, beforeNine)
        listOf(
            options(advance = false) to PiqReminderPlan.NOTIFY_ID_ADVANCE,
            options(onDay = false) to PiqReminderPlan.NOTIFY_ID_ON_DAY,
            options(overdue = false) to PiqReminderPlan.NOTIFY_ID_OVERDUE_FIRST,
        ).forEach { (opt, gone) ->
            val got = run(opt, futureStart, beforeNine)
            assertTrue("id $gone must be gone: ${got.alarms.map { it.notifyId }}", got.alarms.none { it.notifyId == gone })
            val othersBefore = all.alarms.filter { it.notifyId != gone && !(gone == PiqReminderPlan.NOTIFY_ID_OVERDUE_FIRST && it.notifyId == PiqReminderPlan.NOTIFY_ID_OVERDUE_SECOND) }
            val othersAfter = got.alarms.filter { it.notifyId != gone && !(gone == PiqReminderPlan.NOTIFY_ID_OVERDUE_FIRST && it.notifyId == PiqReminderPlan.NOTIFY_ID_OVERDUE_SECOND) }
            assertEquals(othersBefore.map { it.notifyId to it.triggerEpochMillis }, othersAfter.map { it.notifyId to it.triggerEpochMillis })
        }
    }

    @Test
    fun `master off logged out or missing prediction cancels all three kinds`() {
        val cases = listOf(
            options(master = false),
            options(loggedIn = false),
        )
        cases.forEach { opt ->
            assertEquals(emptyList<Int>(), ids(run(opt, futureStart, beforeNine)))
        }
        assertEquals(emptyList<Int>(), ids(run(options(), null, beforeNine)))
    }

    /**
     * 预计开始日已过 ⇒ 只对**提前与当日**取消；逾期正是"开始后还没记录"那段，必须照排。
     * 这一条否掉了旧的两类型写法（把 `predictedStart < 今天` 当成全量早退）。
     */
    @Test
    fun `a start date already past cancels advance and on day but keeps overdue`() {
        val start = LocalDate.of(2026, 10, 17) // 今天是 10-19 ⇒ D+1=10-18 已过、D+3=10-20 未到
        val plan = run(options(), start, beforeNine)
        assertEquals(listOf(PiqReminderPlan.NOTIFY_ID_OVERDUE_SECOND), ids(plan))
        assertEquals(LocalDate.of(2026, 10, 20), plan.alarms.single().triggerDate)
        assertTrue(plan.skipped.contains(PiqReminderPlan.SKIP_PREDICTED_PAST))
    }

    /** 只丢掉**自己到过点**的那一条，其余照排（提示词 §4 第三条腿的独立性的另一半）。 */
    @Test
    fun `only a leg whose own trigger time has passed is dropped`() {
        // 此刻 = 预计开始日当天 10:00（09:00 已过）：当日不再排，提前早过，逾期 D+1 / D+3 仍在未来
        val start = LocalDate.of(2026, 10, 19)
        val ten = ZonedDateTime.of(2026, 10, 19, 10, 0, 0, 0, shanghai)
        val plan = run(options(days = 1), start, ten)
        assertEquals(
            listOf(PiqReminderPlan.NOTIFY_ID_OVERDUE_FIRST, PiqReminderPlan.NOTIFY_ID_OVERDUE_SECOND),
            ids(plan),
        )
        assertTrue(plan.skipped.contains(PiqReminderPlan.SKIP_ON_DAY_TIME_PASSED))
        assertTrue(plan.skipped.contains(PiqReminderPlan.SKIP_ADVANCE_TIME_PASSED))
    }

    @Test
    fun `every trigger already past yields no alarms at all`() {
        val start = LocalDate.of(2026, 10, 10)
        val plan = run(options(), start, beforeNine)
        assertEquals(emptyList<Int>(), ids(plan))
    }

    // ---------------------------------------------------------------- 3. N 的合法域

    @Test
    fun `days outside the allowed set fall back to three`() {
        listOf("0", "4", "6", "8", "abc", "", " ", "-1", "03", "3.0", "null", null).forEach { raw ->
            assertEquals("raw=$raw", 3, PiqReminderPlan.normalizeDays(raw))
        }
        listOf("1", "2", "3", "5", "7").forEach { raw ->
            assertEquals("raw=$raw", raw!!.toInt(), PiqReminderPlan.normalizeDays(raw))
        }
    }

    @Test
    fun `plan clamps an illegal days value to the default and says so`() {
        val plan = run(options(days = 4), futureStart, beforeNine)
        val advance = plan.alarms.first { it.notifyId == PiqReminderPlan.NOTIFY_ID_ADVANCE }
        assertEquals(LocalDate.of(2026, 11, 3), advance.triggerDate)
        assertEquals("距离预计经期还有 3 天", advance.title)
        assertTrue(plan.skipped.contains(PiqReminderPlan.SKIP_DAYS_CLAMPED))
        // 对照：合法值 7 会把同一条推到 10-30，说明"回落"确实改变了产出
        val seven = run(options(days = 7), futureStart, beforeNine)
            .alarms.first { it.notifyId == PiqReminderPlan.NOTIFY_ID_ADVANCE }
        assertNotEquals(advance.triggerEpochMillis, seven.triggerEpochMillis)
        assertEquals(LocalDate.of(2026, 10, 30), seven.triggerDate)
    }

    /**
     * 逾期条数上限。**当前常量下恒等成立**（只有两个 offset，天然 ≤2），本条自己不设防；
     * 判红靠配对使用：变异 B 把 offset 改成 `1..7` 时，本条与
     * [overdue alarms are exactly start plus one and start plus three] 一起报红。
     */
    @Test
    fun `overdue never yields more than two alarms`() {
        val plan = run(options(), futureStart, beforeNine)
        val overdue = plan.alarms.filter {
            it.notifyId == PiqReminderPlan.NOTIFY_ID_OVERDUE_FIRST || it.notifyId == PiqReminderPlan.NOTIFY_ID_OVERDUE_SECOND
        }
        assertTrue("overdue size=${overdue.size}", overdue.size <= PiqReminderPlan.MAX_OVERDUE_ALARMS)
        assertEquals(PiqReminderPlan.MAX_OVERDUE_ALARMS, PiqReminderPlan.OVERDUE_DAY_OFFSETS.size)
    }

    // ---------------------------------------------------------------- 4. 文案逐字

    @Test
    fun `notification texts are verbatim for all three kinds`() {
        val plan = run(options(days = 5), futureStart, beforeNine)
        val byId = plan.alarms.associate { it.notifyId to it }
        assertEquals("距离预计经期还有 5 天", byId.getValue(1).title)
        assertEquals("预计 11月6日开始 · 可在设置里调整提醒", byId.getValue(1).body)
        assertEquals("预计今天开始经期", byId.getValue(2).title)
        assertEquals("预计开始日 11月6日 · 打开 App 记录实际情况", byId.getValue(2).body)
        assertEquals("预计经期已过 1 天", byId.getValue(3).title)
        assertEquals("还没有记录这次的开始日 · 打开 App 记一下吧", byId.getValue(3).body)
        assertEquals("预计经期已过 3 天", byId.getValue(4).title)
        assertEquals("还没有记录这次的开始日 · 打开 App 记一下吧", byId.getValue(4).body)
    }

    // ---------------------------------------------------------------- 5. 边界与时区

    @Test
    fun `advance days crossing a year boundary keeps local nine`() {
        val start = LocalDate.of(2027, 1, 4)
        val now = ZonedDateTime.of(2026, 12, 1, 8, 0, 0, 0, shanghai)
        val plan = run(options(advance = true, onDay = false, overdue = false, days = 5), start, now)
        val alarm = plan.alarms.single()
        assertEquals(LocalDate.of(2026, 12, 30), alarm.triggerDate)
        assertEquals(
            ZonedDateTime.of(2026, 12, 30, 9, 0, 0, 0, shanghai).toInstant().toEpochMilli(),
            alarm.triggerEpochMillis,
        )
        assertEquals("距离预计经期还有 5 天", alarm.title)
        assertEquals("预计 1月4日开始 · 可在设置里调整提醒", alarm.body)
    }

    /**
     * 夏令时：`2026-03-07`（EST，UTC-5）与 `2026-03-08`（EDT，UTC-4）的本地 09:00 只差 **23 小时**。
     * 用"固定偏移 + 24 小时"写的实现会被这一条判红；`zoneId` 也必须跟着时区 id 落盘。
     */
    @Test
    fun `local nine follows the zone across a daylight saving change`() {
        val newYork: ZoneId = ZoneId.of("America/New_York")
        val start = LocalDate.of(2026, 3, 8)
        val now = ZonedDateTime.of(2026, 3, 1, 8, 0, 0, 0, newYork)
        val plan = run(options(advance = true, onDay = true, overdue = false, days = 1), start, now)
        val byId = plan.alarms.associate { it.notifyId to it }
        val advance = byId.getValue(PiqReminderPlan.NOTIFY_ID_ADVANCE)
        val onDay = byId.getValue(PiqReminderPlan.NOTIFY_ID_ON_DAY)
        assertEquals(LocalDate.of(2026, 3, 7), advance.triggerDate)
        assertEquals(LocalDate.of(2026, 3, 8), onDay.triggerDate)
        assertEquals("America/New_York", advance.zoneId)
        val gapHours = (onDay.triggerEpochMillis - advance.triggerEpochMillis) / 3_600_000L
        assertEquals(23L, gapHours)
    }
}
