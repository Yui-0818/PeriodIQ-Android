package io.github.yui0818.periodiq

import io.github.yui0818.periodiq.api.contract.Envelope

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 路由响应体的**外泄面**哨兵（R9.1 的 REG-06：501 分支收口 + 同族两套实现合并成一套）。
 *
 * 判据定义（`AGENTS.md` §3.6 BDD 形态）：
 * 场景：页面在安卓 APK 上点了一个本机没有实现的功能（PDF 导入 / 备份目录 / keepalive）
 *   Given 零权限 WebView、桥直调 `POST /api/v1/import/pdf`
 *   When   路由走 [notImplementedEnvelope]
 *   Then   HTTP 语义 501、体里**只有** `detail` / `code` / `implemented` 三个键，
 *          不出现内部路径 `/api/v1/…`、不出现方法名，用户能读懂 `detail`。
 *
 * 阴性对照（§5.4③"证明判据在真测"）：最后一个用例把一个**带 `path` 的信封**拿来过同一组断言，
 * 必须被这条判据拒掉 —— 否则"键集等于三键"就是个橡皮筋。
 */
class RouterResponseShapeTest {

    private fun keysOf(env: Envelope): Set<String> {
        val json = env.json as JSONObject
        return json.keys().asSequence().toSet()
    }

    @Test
    fun `501 body carries exactly the flat error contract plus implemented flag`() {
        val env = notImplementedEnvelope("PDF 历史导入在安卓端没有解析器。")
        assertEquals(501, env.status)
        assertEquals(setOf("detail", "code", "implemented"), keysOf(env))
        assertEquals("NOT_IMPLEMENTED", (env.json as JSONObject).getString("code"))
        assertFalse((env.json as JSONObject).getBoolean("implemented"))
    }

    @Test
    fun `501 body leaks neither the internal path nor the method name`() {
        val text = notImplementedEnvelope("本机没有该产物形态").toJsonString()
        listOf("/api/v1", "/cycles", "/import", "POST", "GET", "PUT", "DELETE", "twa").forEach { needle ->
            assertFalse("响应体不得含 `$needle`，实测=$text", text.contains(needle))
        }
    }

    @Test
    fun `reason text is a sentence a user can act on`() {
        val detail = (notImplementedEnvelope("可以先在桌面端用「导出备份」得到 JSON 文件，再走 JSON 备份通道导入。")
            .json as JSONObject).getString("detail")
        assertTrue("detail 必须非空，且是一句人能读懂的话", detail.isNotBlank())
        // 名字承诺的是"可行动"⇒ 必须真的含下一步指示（只测非空的话，改坏成"未知错误"也不会红）
        assertTrue("必须给出下一步动作指示，实测=$detail", actionable(detail))
    }

    /**
     * 阴性对照：没有指示词的文案**必须**被判为不可行动，否则上一条用例是一条不会红的断言。
     * 注：本用例钉的是**下面 `actionable` 这个测试内谓词**（`grep -n "actionable" app/src/main` → 0 命中），
     * 不是 `notImplementedEnvelope` 的行为 ⇒ 它是**对照**，不额外提供产品保护（L3-c 复核后标注）。
     */
    @Test
    fun `control negative control a bare error sentence is not actionable`() {
        assertFalse("'未知错误'不算可行动文案", actionable("未知错误"))
        assertFalse("过短文案不算可行动文案", actionable("再试"))
    }

    /** 可行动判据：够长 + 含下一步指示词（与用例名同一口径）。 */
    private fun actionable(detail: String): Boolean =
        detail.length >= 12 && listOf("再", "可以", "请", "下一步").any { detail.contains(it) }

    @Test
    fun `negative control an envelope that still carries path fails the key set judgement`() {
        val leaky = Envelope.failure(
            501, "本机没有该产物形态", "NOT_IMPLEMENTED",
            JSONObject().put("implemented", false).put("path", "/api/v1/backups"),
        )
        assertFalse("带 path 的信封必须被这条判据拒掉（否则哨兵是橡皮筋）",
            keysOf(leaky) == setOf("detail", "code", "implemented"))
        assertTrue(keysOf(leaky).contains("path"))
    }
}
