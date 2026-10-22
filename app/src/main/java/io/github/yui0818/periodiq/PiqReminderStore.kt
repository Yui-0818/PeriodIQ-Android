package io.github.yui0818.periodiq

import android.content.Context
import android.util.Log
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject

/**
 * 排程结果的落盘与复读（提示词 §2②：发通知那一步不许起 Python、不许碰 WebView）。
 *
 * **落点**：`files/notify/schedule-v1.json`（应用私有目录，`allowBackup=false` ⇒ 不参与系统备份；
 * 与 JSON 数据备份无关，那个只从数据库表取行）。写的是**排程计划本身**（本地触发日 + 时区 + 文案），
 * 因此接收器只要读这一个文件就能发通知，不需要库、不需要预测、不需要算法。
 *
 * **为什么落"本地日期 + 时区 id"而不是只落 epoch**：时区变化（`ACTION_TIMEZONE_CHANGED`）之后
 * "本地 09:00"这个语义必须跟着新时区走 ⇒ 重挂时按**当时的** `ZoneId.systemDefault()` 重新求 epoch，
 * 落盘里的 `triggerEpochMillis` 只作为"上一次排程算出来的绝对时刻"留证与对照。
 *
 * 编解码是纯函数（只依赖 `org.json`）⇒ JVM 单测可直接驱动；文件读写留在 [write]/[read]。
 */
/**
 * 提醒计划文件的字节级读写（R13.1 F7）。
 *
 * 单列成 internal 是为了让 JVM 测试能在临时目录上注入“替换时抛错”，验证旧目标/完整
 * `.tmp` 仍可读；生产路径只经由 [PiqReminderStore.write]/[read]。
 */
internal object ReminderStoreFile {

    fun write(target: File, text: String, move: (File, File) -> Unit = ::atomicMove): Boolean {
        val parent = target.parentFile ?: return false
        return runCatching {
            parent.mkdirs()
            val tmp = File(parent, target.name + ".tmp")
            tmp.writeText(text, Charsets.UTF_8)
            move(tmp, target)
        }.isSuccess
    }

    fun read(target: File): String? {
        val direct = runCatching {
            if (target.isFile) target.readText(Charsets.UTF_8) else null
        }.getOrNull()
        if (direct != null) return direct
        val parent = target.parentFile ?: return null
        val tmp = File(parent, target.name + ".tmp")
        return runCatching {
            if (tmp.isFile) tmp.readText(Charsets.UTF_8) else null
        }.getOrNull()
    }

    private fun atomicMove(tmp: File, target: File) {
        Files.move(
            tmp.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }
}

object PiqReminderStore {

    private const val TAG = "PIQ"
    private const val DIR = "notify"
    private const val FILE = "schedule-v1.json"

    /** 落盘形态：一条要挂的闹钟 + 排程那次的上下文（预计开始日、N、生成时刻）。 */
    data class Snapshot(
        val alarms: List<PiqReminderPlan.Alarm>,
        val predictedStart: LocalDate?,
        val days: Int,
        val generatedAtMillis: Long,
    )

    private val EMPTY = Snapshot(emptyList(), null, PiqReminderPlan.DEFAULT_DAYS, 0L)

    fun encode(predictedStart: LocalDate?, days: Int, generatedAtMillis: Long, alarms: List<PiqReminderPlan.Alarm>): String {
        val entries = JSONArray()
        alarms.forEach { alarm ->
            entries.put(
                JSONObject()
                    .put("notifyId", alarm.notifyId)
                    .put("requestCode", alarm.requestCode)
                    .put("triggerDate", alarm.triggerDate.toString())
                    .put("zoneId", alarm.zoneId)
                    .put("triggerEpochMillis", alarm.triggerEpochMillis)
                    .put("title", alarm.title)
                    .put("body", alarm.body),
            )
        }
        return JSONObject()
            .put("version", 1)
            .put("predictedStart", predictedStart?.toString() ?: JSONObject.NULL)
            .put("days", days)
            .put("generatedAtMillis", generatedAtMillis)
            .put("alarms", entries)
            .toString()
    }

    /**
     * 复读落盘内容。**坏一条丢一条**（缺字段 / 日期不可解 / id 不在四条已知通知里），
     * 整份不可解 ⇒ 空快照（等价于"没有排程"），不抛异常给接收器 —— 接收器没有第二次机会恢复。
     */
    fun decode(text: String?): Snapshot {
        if (text.isNullOrBlank()) return EMPTY
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return EMPTY
        val alarms = mutableListOf<PiqReminderPlan.Alarm>()
        val rows = root.optJSONArray("alarms") ?: JSONArray()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val notifyId = row.opt("notifyId") as? Int ?: continue
            if (notifyId !in PiqReminderPlan.ALL_NOTIFY_IDS) continue
            val date = PyMath.parseIsoDate(row.optString("triggerDate")) ?: continue
            val zone = row.optString("zoneId")
            val title = row.optString("title")
            val body = row.optString("body")
            if (title.isBlank() || body.isBlank() || zone.isBlank()) continue
            alarms += PiqReminderPlan.Alarm(
                notifyId = notifyId,
                requestCode = row.optInt("requestCode"),
                triggerDate = date,
                zoneId = zone,
                triggerEpochMillis = row.optLong("triggerEpochMillis"),
                title = title,
                body = body,
            )
        }
        return Snapshot(
            alarms = alarms,
            predictedStart = PyMath.parseIsoDate(root.optString("predictedStart")),
            days = PiqReminderPlan.normalizeDays((root.opt("days") as? Int)?.toString()),
            generatedAtMillis = root.optLong("generatedAtMillis"),
        )
    }

    private fun fileFor(context: Context): File = File(File(context.filesDir, DIR), FILE)

    /**
     * 原子替换（R13.1 F7 / R12-P2-07）：先写完整 `.tmp`，再 `Files.move(..., ATOMIC_MOVE)`。
     * **不再先删旧目标**；替换失败时旧目标原样保留，`read` 也可以回退读完整 `.tmp`。
     */
    fun write(context: Context, predictedStart: LocalDate?, days: Int, alarms: List<PiqReminderPlan.Alarm>): Boolean {
        val target = fileFor(context)
        val text = encode(predictedStart, days, System.currentTimeMillis(), alarms)
        val ok = ReminderStoreFile.write(target, text)
        Log.i(
            TAG,
            "reminder-store|ok=$ok|path=${target.path}|bytes=${text.length}" +
                "|alarms=${alarms.size}|hasPrediction=${predictedStart != null}|days=$days",
        )
        return ok
    }

    fun read(context: Context): Snapshot = decode(ReminderStoreFile.read(fileFor(context)))

    /** 清空排程（全取消时调用；文件留着写一份空清单，免得接收器读到上一轮的旧计划）。 */
    fun clear(context: Context): Boolean = write(context, null, PiqReminderPlan.DEFAULT_DAYS, emptyList())
}
