package io.github.yui0818.periodiq

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 排程落盘层的编解码哨兵（R11）。
 *
 * **为什么单独一层要测**：接收器不许起 Python、不许碰 WebView，它唯一的输入就是
 * `files/notify/schedule-v1.json` 复读出来的这份快照（提示词 §2②）。所以"四条腿都能被
 * 原样读回来"和"读不回来时给空快照而不是抛异常"这两件事，必须在 JVM 上钉住 ——
 * 设备侧只验证通知真的弹了，验证不了畸形行会被怎么对待。
 *
 * 判红手段：[unknown notify ids are dropped without touching the valid rows] 是阴性对照
 * （往文件里塞一条 id=99 的行，必须被丢掉且其余三条原样活着）；把 [PiqReminderStore.decode]
 * 的 id 白名单去掉，这条立刻变红。
 */
class PiqReminderStoreTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val start: LocalDate = LocalDate.of(2026, 11, 6)
    private val now: ZonedDateTime = ZonedDateTime.of(2026, 10, 19, 8, 0, 0, 0, zone)

    private fun fullPlan(): PiqReminderPlan.Plan = PiqReminderPlan.plan(
        PiqReminderPlan.Options(
            masterEnabled = true,
            advanceEnabled = true,
            onDayEnabled = true,
            overdueEnabled = true,
            days = 3,
            loggedIn = true,
        ),
        start,
        now,
    )

    @Test
    fun `all four alarms survive an encode decode round trip`() {
        val plan = fullPlan()
        assertEquals(4, plan.alarms.size)
        val text = PiqReminderStore.encode(start, 3, 1_700_000_000_000L, plan.alarms)
        val back = PiqReminderStore.decode(text)
        assertEquals(plan.alarms, back.alarms)
        assertEquals(start, back.predictedStart)
        assertEquals(3, back.days)
        assertEquals(1_700_000_000_000L, back.generatedAtMillis)
    }

    @Test
    fun `unknown notify ids are dropped without touching the valid rows`() {
        val plan = fullPlan()
        val polluted = PiqReminderStore
            .encode(start, 3, 0L, plan.alarms)
            // 手写一条完全合法但 id 未知的行塞进数组头部：整份 JSON 仍可解析，
            // 只有这一行该被白名单挡掉（上一版探针是把坏行拼在中途，制造了语法坏掉的整份文件，
            // 于是断言测的是"整份读不回"而不是"这一条被丢掉"）。
            .replace(
                "\"alarms\":[",
                "\"alarms\":[{\"notifyId\":99,\"requestCode\":99,\"triggerDate\":\"2026-11-06\"," +
                    "\"zoneId\":\"Asia/Shanghai\",\"triggerEpochMillis\":1,\"title\":\"坏行\",\"body\":\"坏行\"},",
            )
        val back = PiqReminderStore.decode(polluted)
        assertEquals(listOf(1, 2, 3, 4), back.alarms.map { it.notifyId }.sorted())
        assertTrue(back.alarms.none { it.notifyId == 99 })
    }

    @Test
    fun `unparsable or blank content decodes to an empty snapshot instead of throwing`() {
        listOf(null, "", "   ", "not json", "{\"alarms\":\"oops\"}", "{}").forEach { raw ->
            val snapshot = PiqReminderStore.decode(raw)
            assertNotNull(raw, snapshot)
            assertEquals("raw=$raw", emptyList<PiqReminderPlan.Alarm>(), snapshot.alarms)
        }
    }

    @Test
    fun `a stored days value outside the allowed set reads back as the default`() {
        val text = PiqReminderStore.encode(start, 3, 0L, fullPlan().alarms)
            .replace("\"days\":3", "\"days\":4")
        assertEquals(PiqReminderPlan.DEFAULT_DAYS, PiqReminderStore.decode(text).days)
    }

    @Test
    fun `a row with a missing or unparsable date is dropped`() {
        val alarm = fullPlan().alarms.first()
        val broken = PiqReminderStore.encode(start, 3, 0L, listOf(alarm))
            .replace("\"triggerDate\":\"2026-11-03\"", "\"triggerDate\":\"11月3号\"")
        assertEquals(emptyList<PiqReminderPlan.Alarm>(), PiqReminderStore.decode(broken).alarms)
    }
}
