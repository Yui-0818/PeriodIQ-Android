package io.github.yui0818.periodiq

import io.github.yui0818.periodiq.api.contract.ApiError
import io.github.yui0818.periodiq.domain.import.ImportGate

import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R8 导入路径与桌面的**口径逐值对齐**哨兵（`docs/契约/跨端契约.md` §1/§2 · `AGENTS.md` §6 第 1 条）。
 *
 * 判据定义（每条都是可测量结果，非法输入一律显式给出期望的状态码与 code）：
 *
 * 1. **空导入闸门**：桌面 `settings.import_data` 写的是 `if not body.cycles and not body.model_params`
 *    —— Python 真值判断，`{}` 与 `null` 都算"无内容"⇒ 必须 400 `EMPTY_IMPORT`。
 *    安卓的导入是**清空重建**型单事务（先 `deleteUserCycles`/`deleteUserPredictions` 再插入），
 *    闸门漏判一次就把用户已有的全部周期与历史预测抹掉 ⇒ 按 P0 对待。
 * 2. **`model_version`**：桌面 `str(p.get("model_version", "unknown"))` ⇒ 只有**键缺失**才回退
 *    `"unknown"`；显式 `null` 得到 `"None"`、空串得到 `""`、数字得到 `"5"`，三者都不在白名单 ⇒ 400。
 * 3. **导入日期**：桌面 `settings._parse_iso_date` 用 `date.fromisoformat(value)`
 *    （实测 3.11.15：接受 `YYYY-MM-DD` 与 `YYYYMMDD`，拒绝空串/尾随垃圾/时间后缀/不存在的日期）；
 *    键缺失或显式 `null` 仅在 `required=False` 时合法。**空串不得当作"缺失"**。
 * 4. **POST/PUT 日期**：桌面走 pydantic `date` 字段（实测 2.13.4：只接受 `YYYY-MM-DD`，
 *    `20260902` 也拒）⇒ 与导入路径的接受集**不同**，两条路径不得共用一个宽松解析器。
 *
 * 期望取值不是推断：两个解释器的实测对照表（15 种形态 × JVM `ISO_LOCAL_DATE`/`BASIC_ISO_DATE` /
 * Python `date.fromisoformat` / pydantic `TypeAdapter(date)`）见 R8 报告 §3
 * 与 `.dsh/r8_probe/logs/date_parity_*.txt`。
 */
class ImportParityTest {

    /** 生产调用点给 `requireImportDate` 的**文案标签**（与 JSON 键不同形，见 R9 的 R8-B1）。 */
    private val CYCLE_START = "cycles[].period_start"
    private val CYCLE_END = "cycles[].period_end"

    // ---------- 1. 空导入闸门 ----------

    @Test
    fun `empty cycles plus empty model_params object is not an importable payload`() {
        val body = JSONObject().put("cycles", JSONArray()).put("model_params", JSONObject())
        assertFalse(
            "桌面 `not {}` 为真 ⇒ 必须判空并回 EMPTY_IMPORT；放行会导致清空重建抹掉用户数据",
            ImportGate.hasImportablePayload(body),
        )
    }

    @Test
    fun `null model_params alone is not an importable payload`() {
        assertFalse(
            ImportGate.hasImportablePayload(
                JSONObject().put("cycles", JSONArray()).put("model_params", JSONObject.NULL),
            ),
        )
    }

    @Test
    fun `absent model_params alone is not an importable payload`() {
        assertFalse(ImportGate.hasImportablePayload(JSONObject().put("cycles", JSONArray())))
    }

    @Test
    fun `a completely empty body is not an importable payload`() {
        assertFalse(ImportGate.hasImportablePayload(JSONObject()))
    }

    @Test
    fun `a single cycle is an importable payload`() {
        val body = JSONObject().put(
            "cycles",
            JSONArray().put(JSONObject().put("period_start", "2026-09-01")),
        )
        assertTrue(ImportGate.hasImportablePayload(body))
    }

    @Test
    fun `non empty model_params is an importable payload`() {
        val body = JSONObject().put("cycles", JSONArray())
            .put("model_params", JSONObject().put("mean_cycle", 28.4))
        assertTrue(ImportGate.hasImportablePayload(body))
    }

    // ---------- 2. model_version ----------

    @Test
    fun `absent model_version falls back to unknown`() {
        assertEquals("unknown", ImportGate.normalizeModelVersion(JSONObject()))
    }

    @Test
    fun `explicit null model_version must not be folded into the whitelist`() {
        val normalized = ImportGate.normalizeModelVersion(JSONObject().put("model_version", JSONObject.NULL))
        assertFalse(
            "显式 null 必须解析成不在白名单里的串（桌面 str(None) == 'None'），实测得到=$normalized",
            PiqConfig.ALLOWED_MODEL_VERSIONS.contains(normalized),
        )
    }

    @Test
    fun `empty string model_version must not be folded into the whitelist`() {
        val normalized = ImportGate.normalizeModelVersion(JSONObject().put("model_version", ""))
        assertFalse(
            "桌面 str('') == ''，不在白名单 ⇒ 400；实测得到=$normalized",
            PiqConfig.ALLOWED_MODEL_VERSIONS.contains(normalized),
        )
    }

    @Test
    fun `numeric model_version is stringified like desktop and rejected`() {
        val normalized = ImportGate.normalizeModelVersion(JSONObject().put("model_version", 5))
        assertEquals("5", normalized)
        assertFalse(PiqConfig.ALLOWED_MODEL_VERSIONS.contains(normalized))
    }

    @Test
    fun `string model_version passes through unchanged`() {
        assertEquals(
            "bayesian_v1",
            ImportGate.normalizeModelVersion(JSONObject().put("model_version", "bayesian_v1")),
        )
    }

    // ---------- 3. 导入日期 ----------

    @Test
    fun `iso date is accepted on the import path`() {
        assertEquals(
            LocalDate.of(2026, 9, 2),
            ImportGate.requireImportDate(JSONObject().put("period_start", "2026-09-02"), "period_start", CYCLE_START),
        )
    }

    @Test
    fun `basic format date that desktop accepts must import`() {
        assertEquals(
            LocalDate.of(2026, 9, 2),
            ImportGate.requireImportDate(JSONObject().put("period_start", "20260902"), "period_start", CYCLE_START),
        )
    }

    @Test
    fun `empty string date is rejected rather than treated as absent`() {
        // 安卓旧行为：textOrNull("") 塌成 null ⇒ required=False 的 period_end/actual_date
        // 被静默写成 NULL，把"文件里写了空日期"伪装成"这一列本来就没填"。
        expectInvalidImportDate(JSONObject().put("period_end", ""), key = "period_end", label = CYCLE_END, optional = true)
        expectInvalidImportDate(JSONObject().put("period_end", ""), key = "period_end", label = CYCLE_END, optional = false)
    }

    @Test
    fun `trailing garbage date is rejected`() {
        expectInvalidImportDate(JSONObject().put("period_start", "2026-09-02ABC"))
    }

    @Test
    fun `date with time suffix is rejected`() {
        expectInvalidImportDate(JSONObject().put("period_start", "2026-09-02T10:30"))
    }

    @Test
    fun `out of range day is rejected`() {
        expectInvalidImportDate(JSONObject().put("period_start", "2026-02-30"))
        expectInvalidImportDate(JSONObject().put("period_start", "2026-02-29"))
    }

    @Test
    fun `leap day in a leap year is accepted`() {
        assertEquals(
            LocalDate.of(2024, 2, 29),
            ImportGate.requireImportDate(JSONObject().put("period_start", "2024-02-29"), "period_start", CYCLE_START),
        )
    }

    @Test
    fun `non string date value is rejected`() {
        expectInvalidImportDate(JSONObject().put("period_start", 20260902))
        expectInvalidImportDate(JSONObject().put("period_start", JSONArray()))
    }

    @Test
    fun `json null and missing key are the only legal omissions of an optional date`() {
        assertNull(
            ImportGate.optionalImportDate(
                JSONObject().put("period_end", JSONObject.NULL), "period_end", CYCLE_END,
            ),
        )
        assertNull(ImportGate.optionalImportDate(JSONObject(), "period_end", CYCLE_END))
    }

    @Test
    fun `missing key on a required date is rejected with the desktop message`() {
        val error = runCatching { ImportGate.requireImportDate(JSONObject(), "period_start", CYCLE_START) }.exceptionOrNull()
        assertTrue("应报 ApiError，实测=$error", error is ApiError)
        val apiError = error as ApiError
        assertEquals(400, apiError.status)
        assertEquals("INVALID_IMPORT", apiError.code)
        assertEquals(
            "cycles[].period_start 缺失或不是合法的 ISO 日期（YYYY-MM-DD）",
            apiError.detail,
        )
    }

    // ---------- 5. 容器形态（桌面 pydantic 给 422，安卓不得静默折空）----------

    @Test
    fun `a string cycles must be rejected with 422 rather than silently emptied`() {
        // 后果链：optJSONArray 折成空数组 ⇒ 只要 model_params 非空就过 EMPTY 闸门
        //        ⇒ 清空重建 ⇒ 用户全部周期与预测被删光，还回 200 "成功导入 0 条"。
        val body = JSONObject().put("cycles", "abc")
            .put("model_params", JSONObject().put("mean_cycle", 28.0))
        assertTrue(
            "前置事实：这个体确实过了空导入闸门（说明形态校验是唯一挡在删库前面的东西）",
            ImportGate.hasImportablePayload(body),
        )
        expectValidationError(body)
    }

    @Test
    fun `an object cycles must be rejected`() {
        expectValidationError(JSONObject().put("cycles", JSONObject()).put("predictions", JSONArray()))
    }

    @Test
    fun `a numeric predictions must be rejected`() {
        expectValidationError(JSONObject().put("predictions", 5))
    }

    @Test
    fun `a string model_params must be rejected`() {
        expectValidationError(JSONObject().put("cycles", JSONArray()).put("model_params", "{}"))
    }

    @Test
    fun `explicit null model_params stays legal`() {
        // 桌面 `Optional[dict] = None` 允许 null（等价于没给），不得因此报错
        ImportGate.requirePayloadShapes(
            JSONObject().put("cycles", JSONArray()).put("model_params", JSONObject.NULL),
        )
    }

    @Test
    fun `well shaped and partially absent payloads pass`() {
        ImportGate.requirePayloadShapes(JSONObject())
        ImportGate.requirePayloadShapes(
            JSONObject().put("cycles", JSONArray().put(JSONObject().put("period_start", "2026-09-01")))
                .put("predictions", JSONArray()),
        )
    }

    private fun expectValidationError(body: JSONObject) {
        val error = runCatching { ImportGate.requirePayloadShapes(body) }.exceptionOrNull()
        assertTrue("容器形态非法必须抛 ApiError(422)，实测=$error", error is ApiError)
        val apiError = error as ApiError
        assertEquals("桌面 pydantic 校验失败经全局 handler 展平是 422", 422, apiError.status)
        assertEquals("VALIDATION_ERROR", apiError.code)
    }

    private fun expectInvalidImportDate(
        row: JSONObject,
        key: String = "period_start",
        label: String = "cycles[].period_start",
        optional: Boolean = true,
    ) {
        val error = runCatching {
            if (optional) ImportGate.optionalImportDate(row, key, label) else ImportGate.requireImportDate(row, key, label)
        }.exceptionOrNull()
        assertTrue("应为非法日期，实测没有抛错：error=$error", error is ApiError)
        val apiError = error as ApiError
        assertEquals("非法日期必须落 400（与桌面 _parse_iso_date 同码）", 400, apiError.status)
        assertEquals("INVALID_IMPORT", apiError.code)
    }

    // ---------- 4. 两条入站路径的接受集必须不同 ----------

    @Test
    fun `import parser accepts the iso and basic formats and nothing junkier`() {
        val accepted = listOf("2026-09-02", "20260902", "2024-02-29")
        val rejected = listOf("", " ", "2026-9-2", "2026-02-30", "20260230", "2026-13-01", "20261301",
            " 2026-09-02", "2026-09-02T10:30", "2026-09-02ABC", "+2026-09-02", "2026-09-02 ")
        assertEquals(" accepted 里的每一种形态都必须真被解析（漏一个即口径不符）", accepted,
            accepted.filter { PyMath.parseDateFromIsoFormat(it) != null })
        for (form in rejected) assertNull("导入侧应拒绝 '$form'", PyMath.parseDateFromIsoFormat(form))
        for (nonString in listOf(20260902, 3.5, JSONArray(), JSONObject.NULL)) {
            assertNull("非字符串形态一律拒绝（桌面 fromisoformat 对非 str 抛），实测=$nonString",
                PyMath.parseDateFromIsoFormat(nonString))
        }
    }

    @Test
    fun `api parser is stricter than import parser`() {
        // 桌面 pydantic 2.13.4 对 POST/PUT 的 date 字段只收 YYYY-MM-DD（20260902 也拒）
        assertEquals(LocalDate.of(2026, 9, 2), PyMath.parseDateStrictIso("2026-09-02"))
        assertNull("基本格式不得被 POST/PUT 路径接受（桌面 pydantic 实测拒绝）", PyMath.parseDateStrictIso("20260902"))
        assertNull(PyMath.parseDateStrictIso("2026-09-02ABC"))
        assertNull(PyMath.parseDateStrictIso(""))
        assertNull(PyMath.parseDateStrictIso(null))
    }

    // ---------- 6. 日期域边界（L2-1 的 F-01 / F-04 实测证伪了我原来的"接受集逐形一致"断言）----------

    @Test
    fun `year zero is out of python date domain and must be rejected on both paths`() {
        // Python `date.fromisoformat('0000-01-01')` 与 pydantic 都 REJECT，而 java.time 收 year 0；
        // 让它入库的后果：之后任何触发全量重算的删除操作会在这条行上抛 ⇒ `DELETE /cycles/{id}` 永久 500。
        for (path in listOf("0000-01-01", "0000-12-31")) {
            assertNull("导入侧必须拒绝年 0：$path", PyMath.parseDateFromIsoFormat(path))
            assertNull("POST/PUT 侧必须拒绝年 0：$path", PyMath.parseDateStrictIso(path))
        }
        assertEquals("年 1 是 Python date 的下界，两侧都必须收",
            LocalDate.of(1, 1, 1), PyMath.parseDateFromIsoFormat("0001-01-01"))
        assertEquals(LocalDate.of(1, 1, 1), PyMath.parseDateStrictIso("0001-01-01"))
        assertEquals(LocalDate.of(9999, 12, 31), PyMath.parseDateFromIsoFormat("9999-12-31"))
    }

    @Test
    fun `iso week dates are accepted by the import path because desktop accepts them`() {
        // Python 3.11 实测：date.fromisoformat('2025-W02-7') == date(2025,1,12)
        assertEquals(LocalDate.of(2025, 1, 12), PyMath.parseDateFromIsoFormat("2025-W02-7"))
        // 而 pydantic（POST/PUT 路径）不收周形式 ⇒ 两条路径这里必须分叉
        assertNull(PyMath.parseDateStrictIso("2025-W02-7"))
    }

    @Test
    fun `week date oddities the desktop rejects must stay rejected`() {
        for (form in listOf("2025-w02-7", "2025-W02-8", "2025-W53-1", "2025-W02-0", "2025-W2-7")) {
            assertNull("周形式必须是 \\d{4}-W\\d{2}-[1-7]（小写 w 桌面不收）：$form",
                PyMath.parseDateFromIsoFormat(form))
        }
    }

    // ---------- 7. confidence 类型域 与 非 UTF-8 文件（L2 的 F-07 / F-05）----------

    @Test
    fun `numeric string confidence must not be accepted like desktop does not`() {
        // 桌面：`isinstance(confidence, (int,float)) and not isinstance(confidence, bool)`
        //   ⇒ `"0.5"` 不是数字 ⇒ 400。安卓的 doubleOrNull 会把数字字符串转出来 ⇒ 脏值入库。
        val stringNumber = ImportGate.confidenceValue(JSONObject().put("confidence", "0.5"))
        assertNull("数字字符串必须视为无值（→ 400），实测=$stringNumber", stringNumber)
        assertNull(ImportGate.confidenceValue(JSONObject().put("confidence", true)))
        assertNull(ImportGate.confidenceValue(JSONObject().put("confidence", JSONObject.NULL)))
        assertEquals("0.5 本身合法", 0.5, ImportGate.confidenceValue(JSONObject().put("confidence", 0.5))!!, 1e-9)
        assertEquals("整数 1 也是合法置信度（桌面 int 属于 (int,float)）", 1.0,
            ImportGate.confidenceValue(JSONObject().put("confidence", 1))!!, 1e-9)
    }

    @Test
    fun `a body carrying the unicode replacement character must be flagged not silently stored`() {
        // 后果：GBK/ANSI 备份被前端以 utf-8 非致命解码读成 U+FFFD，导入回 200"已导入 1 条"，
        // 中文备注在库里已经是乱码 —— 用户看不到任何异常。
        val mojibake = JSONObject().put("cycles", JSONArray().put(
            JSONObject().put("period_start", "2026-09-01").put("note", "周期\uFFFD\uFFFD记录"),
        ))
        assertTrue("含 U+FFFD 必须被标出", ImportGate.hasReplacementCharacter(mojibake))
        assertFalse(ImportGate.hasReplacementCharacter(
            JSONObject().put("cycles", JSONArray().put(
                JSONObject().put("period_start", "2026-09-01").put("note", "正常中文备注"),
            )),
        ))
    }

    @Test
    fun `lax db reader stays lax for timestamps but is no longer used inbound`() {
        // 库内 created_at 是 `YYYY-MM-DD HH:MM:SS`，宽松截断读取仍是需要的（单一口径）
        assertEquals(LocalDate.of(2026, 9, 2), PyMath.parseIsoDate("2026-09-02 18:13:22"))
    }
}
