package io.github.yui0818.periodiq

import io.github.yui0818.periodiq.api.contract.ApiError
import io.github.yui0818.periodiq.api.contract.Envelope
import io.github.yui0818.periodiq.domain.import.ImportBodyGate

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导入体的**四档可读错误**与体积闸门哨兵（R9 的 REG-05 / REG-02 / REG-03）。
 *
 * 判据定义（每条都给"哪一档、什么状态码、什么 code、文案里必须出现的词"）：
 *  ① 空文件 → 422 `VALIDATION_ERROR`，文案含"空的"
 *  ② 不是合法 JSON → 422，文案含"不是合法的 JSON"
 *  ③ 是 JSON 但最外层不是对象 → 422，文案含实际读到的种类（数组/字符串/…）
 *  ④ 是对象但没有必要键 → 400 `EMPTY_IMPORT`，文案含"没有 cycles"
 *  体积闸门 → 400 `IMPORT_TOO_LARGE`，且在**解析之前**（REG-02）
 *  BOM → 剥掉后照常解析（REG-03，安卓比桌面宽一档，登记在报告 §6）
 *
 * 阳性对照 = 合法最小备份必须解析成功并原样给出 cycles；
 * 阴性对照 = 上面每一档都必须报红。
 */
class ImportBodyGateTest {

    private val minimalValid = """{"cycles":[{"period_start":"2026-01-05"}],"predictions":[],"model_params":{}}"""

    @Test
    fun `positive control - a minimal valid backup body parses`() {
        val body = ImportBodyGate.parse(minimalValid)
        assertEquals(1, body.getJSONArray("cycles").length())
        assertEquals("2026-01-05", body.getJSONArray("cycles").getJSONObject(0).getString("period_start"))
    }

    @Test
    fun `tier 1 - empty and blank bodies get their own sentence`() {
        for (form in listOf(null, "", "   \n ")) {
            val err = assertThrows("空体必须拒：[$form]", ApiError::class.java) { ImportBodyGate.parse(form) }
            assertEquals("[$form] 状态码", 422, err.status)
            assertEquals("[$form] code", "VALIDATION_ERROR", err.code)
            assertTrue("[$form] 文案必须是'空文件'这一档，实测=${err.detail}", err.detail.contains("空的"))
        }
    }

    @Test
    fun `tier 2 - syntax errors name the position instead of one generic sentence`() {
        val err = assertThrows(ApiError::class.java) { ImportBodyGate.parse("""{"cycles":[],}""") }
        assertEquals(422, err.status)
        assertTrue("实测=${err.detail}", err.detail.contains("不是合法的 JSON"))
        assertTrue("文案要带位置（第 N 个字符），实测=${err.detail}", Regex("第 \\d+ 个字符").containsMatchIn(err.detail))
    }

    @Test
    fun `tier 2 - the desktop-rejected lenient forms are rejected here too`() {
        listOf(
            "单引号" to """{'cycles':[]}""",
            "注释" to """// x{"cycles":[]}""",
            "十六进制" to """{"cycles":[],"x":0x10}""",
            "尾随垃圾" to """{"cycles":[]} trailing""",
        ).forEach { (name, form) ->
            val err = assertThrows("[$name] 必须拒", ApiError::class.java) { ImportBodyGate.parse(form) }
            assertEquals("[$name] 要落在②档", 422, err.status)
            assertTrue("[$name] 实测=${err.detail}", err.detail.contains("不是合法的 JSON"))
        }
    }

    @Test
    fun `tier 3 - valid json whose top level is not an object says so`() {
        val cases = listOf("数组" to "[]", "字符串" to "\"备份\"", "数字" to "5")
        for ((name, form) in cases) {
            val err = assertThrows("[$name] 必须拒", ApiError::class.java) { ImportBodyGate.parse(form) }
            assertEquals(422, err.status)
            assertTrue("[$name] 文案要点出实际种类，实测=${err.detail}", err.detail.contains("最外层是"))
        }
    }

    @Test
    fun `tier 4 - an object without the required keys is EMPTY_IMPORT`() {
        val err = assertThrows(ApiError::class.java) { ImportBodyGate.parse("""{"app_settings":{}}""") }
        assertEquals(400, err.status)
        assertEquals("EMPTY_IMPORT", err.code)
        assertTrue("实测=${err.detail}", err.detail.contains("没有 cycles"))
    }

    @Test
    fun `bom is stripped and the file still imports - one tier wider than desktop`() {
        val withBom = "\uFEFF" + minimalValid
        val body = ImportBodyGate.parse(withBom)
        assertEquals(1, body.getJSONArray("cycles").length())
    }

    @Test
    fun `oversize body is refused before parsing`() {
        val huge = "x".repeat((PiqConfig.MAX_IMPORT_BYTES + 1).toInt())
        val err = assertThrows(ApiError::class.java) { ImportBodyGate.parse(huge) }
        assertEquals(400, err.status)
        assertEquals("IMPORT_TOO_LARGE", err.code)
        assertTrue("实测=${err.detail}", err.detail.contains("太大"))
        assertFalse("文案里的数字必须已被替换，不得把 %.1f 甩给用户：${err.detail}", err.detail.contains("%."))
        assertTrue("要给出实测体积与上限（可测数字），实测=${err.detail}",
            Regex("""\d+\.\d+ MB""").containsMatchIn(err.detail) && err.detail.contains("8 MB"))
    }

    @Test
    fun `utf8 byte counting matches the platform encoder on every width`() {
        listOf("", "abc", "周期备注", "é", "😀", "a周é😀").forEach { sample ->
            assertEquals("'$sample'", sample.toByteArray(Charsets.UTF_8).size.toLong(), ImportBodyGate.utf8Bytes(sample))
        }
    }

    @Test
    fun `the measured worst legal backup still fits the limit`() {
        // 依据 R9 报告 §3 的体积实测（同一份最大合法备份的 UTF-8 形态）：
        // 1000 周期 × 2000 字中文备注 + 2000 预测（各 200 字 model_params_json）= 6,834,114 B
        val measuredWorstLegalBytes = 6_834_114L
        assertTrue(
            "上限必须容得下实测最大合法备份（${measuredWorstLegalBytes} B），否则 REG-02 会把用户挡在门外",
            measuredWorstLegalBytes < PiqConfig.MAX_IMPORT_BYTES,
        )
        // 边界必须可红：把体积闸门的 `>` 改成 `>=` 时，下面这条立刻变红（原来那行是拿字面量比自己的定义式，恒真）
        val atLimit = "x".repeat(PiqConfig.MAX_IMPORT_BYTES.toInt())
        val boundary = assertThrows("正好等于上限的体仍然不是合法 JSON，必须报错", ApiError::class.java) {
            ImportBodyGate.parse(atLimit)
        }
        assertNotEquals("正好等于上限不得被体积闸门挡下（REG-02 的口径是「超过」才拒）", "IMPORT_TOO_LARGE", boundary.code)
    }

    @Test
    fun `single-source failure envelope keeps the flat desktop contract`() {
        // REG-06：`Envelope.error` 与内联构造收成一处（`Envelope.failure` 单源），形态仍是 {detail, code}
        val fromError = Envelope.error(ApiError(404, "x", "NOT_FOUND"))
        val fromFailure = Envelope.failure(404, "x", "NOT_FOUND")
        assertEquals(fromError.toJsonString(), fromFailure.toJsonString())
        val json = JSONObject(fromFailure.toJsonString()).getJSONObject("json")
        assertEquals("x", json.getString("detail"))
        assertEquals("NOT_FOUND", json.getString("code"))
        val withExtra = Envelope.error(ApiError(400, "数据异常，需确认", "DATA_ANOMALY", JSONObject().put("anomalies", 3)))
        assertEquals(3, JSONObject(withExtra.toJsonString()).getJSONObject("json").getInt("anomalies"))
    }
}
