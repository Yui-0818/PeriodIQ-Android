package io.github.yui0818.periodiq

import io.github.yui0818.periodiq.api.contract.ApiError
import io.github.yui0818.periodiq.domain.import.ImportGate

import java.time.LocalDate
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 导入日期字段的**键 / 文案标签**分离哨兵（R9 的 R8-B1，P1）。
 *
 * 判据定义：`ImportGate.requireImportDate` / `optionalImportDate` 必须
 * **按 JSON 键取值**，而把 `cycles[].period_start` 这类**文案标签**只用在错误信息里。
 * 两者合成一个参数时，`row.has("cycles[].period_start")` 恒为 false ⇒
 * 任何合法备份都被拒（`400 INVALID_IMPORT`），导入这条核心链路对用户完全不可用
 * （R8 引入的回归；运行时读数见 R9 报告 §3）。
 *
 * 三条各管一件事：
 *  1. 良品形态必须通过（这条在被还原成"标签当键"时**必然变红**）；
 *  2. 键缺失 / 显式 null / 空串必须 400，且文案里带的是标签（与桌面 `INVALID_IMPORT` 同族）；
 *  3. 选填日期：缺失与显式 null 合法，垃圾形态仍须 400。
 */
class ImportDateFieldsTest {

    private val cycleLabel = "cycles[].period_start"

    @Test
    fun `a backup row carrying the real key must validate`() {
        val row = JSONObject()
            .put("period_start", "2026-01-05")
            .put("period_end", "2026-01-10")
        assertEquals(LocalDate.of(2026, 1, 5), ImportGate.requireImportDate(row, "period_start", cycleLabel))
        assertEquals(LocalDate.of(2026, 1, 10), ImportGate.optionalImportDate(row, "period_end", "cycles[].period_end"))
    }

    /**
     * 这张表是生产调用点的唯一入口 ⇒ 逐字段验三件事：
     * ① 标签的最后一段就是键（写反/拼错就红）；
     * ② 带真实键的行能取值（走的是 `field.require/optional`，与路由同一条路）；
     * ③ 只带标签的行必须 400（证明取值真的认键、不认标签）。
     */
    @Test
    fun `every production date field reads by its key and reports by its label`() {
        for (field in ImportGate.IMPORT_DATE_FIELDS) {
            assertEquals("标签的最后一段必须等于 JSON 键：$field",
                field.key, field.label.substringAfterLast('.'))
            val good = JSONObject().put(field.key, "2026-01-05")
            assertEquals("$field 应取到 2026-01-05", java.time.LocalDate.of(2026, 1, 5), field.require(good))
            val labelOnly = JSONObject().put(field.label, "2026-01-05")
            val err = assertThrows("只给标签时必须 400：$field", ApiError::class.java) { field.require(labelOnly) }
            assertEquals("INVALID_IMPORT", err.code)
            assertEquals("${field.label} 缺失或不是合法的 ISO 日期（YYYY-MM-DD）", err.detail)
        }
    }

    @Test
    fun `missing null and empty values are rejected with the label in the message`() {
        listOf(
            "键缺失" to JSONObject().put("note", "x"),
            "显式 null" to JSONObject().put("period_start", JSONObject.NULL),
            "空串" to JSONObject().put("period_start", ""),
            "非字符串" to JSONObject().put("period_start", 20260105),
        ).forEach { (name, row) ->
            val err = assertThrows("[$name] 必须 400", ApiError::class.java) {
                ImportGate.requireImportDate(row, "period_start", cycleLabel)
            }
            assertEquals("[$name] 状态码", 400, err.status)
            assertEquals("[$name] code", "INVALID_IMPORT", err.code)
            assertEquals("[$name] detail 带的是文案标签",
                "$cycleLabel 缺失或不是合法的 ISO 日期（YYYY-MM-DD）", err.detail)
        }
    }

    @Test
    fun `optional import date tolerates absent and null but not garbage`() {
        assertEquals(null, ImportGate.optionalImportDate(JSONObject(), "period_end", "cycles[].period_end"))
        assertEquals(null, ImportGate.optionalImportDate(
            JSONObject().put("period_end", JSONObject.NULL), "period_end", "cycles[].period_end"))
        assertThrows("空串不得当成缺失", ApiError::class.java) {
            ImportGate.optionalImportDate(JSONObject().put("period_end", ""), "period_end", "cycles[].period_end")
        }
    }

    @Test
    fun `fromisoformat accepted forms still hold after the split`() {
        val row = JSONObject().put("period_start", "20260902")
        assertEquals(LocalDate.of(2026, 9, 2), ImportGate.requireImportDate(row, "period_start", cycleLabel))
    }
}
