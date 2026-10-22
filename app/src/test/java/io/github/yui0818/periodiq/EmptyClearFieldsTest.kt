package io.github.yui0818.periodiq

import io.github.yui0818.periodiq.api.contract.Envelope

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 可清空字段的空串语义（R13.1 F2 / R12-P2-01）。
 *
 * 场景：用户在备注/昵称输入框里**删光**再保存
 *   Given 一条已有备注的周期、一个已有昵称的账号
 *   When  桥发出 `PUT /cycles/<id>`（`note: ""`）与 `PUT /profile`（`nickname: ""`）
 *   Then  库里这两列变成 `""`（不是保持旧值），且注册/登录对空口令的拒绝口径不变
 *
 * 红路径：把 [optionalStringAllowingEmpty] 改回 `body.textOrNull(key)` ⇒ 第 2 条立刻红；
 * 把调用点换回 `optionalString` ⇒ 第 4 条立刻红。
 */
class EmptyClearFieldsTest {

    @Test
    fun `missing and explicit null mean not provided`() {
        assertNull(optionalStringAllowingEmpty(JSONObject(), "note"))
        assertNull(optionalStringAllowingEmpty(JSONObject().put("note", JSONObject.NULL), "note"))
    }

    @Test
    fun `empty string is preserved so the update layer can clear the column`() {
        assertEquals("", optionalStringAllowingEmpty(JSONObject().put("note", ""), "note"))
        assertEquals("", optionalStringAllowingEmpty(JSONObject().put("nickname", ""), "nickname"))
    }

    @Test
    fun `non empty and non string values keep the old acceptance`() {
        assertEquals("abc", optionalStringAllowingEmpty(JSONObject().put("note", "abc"), "note"))
        assertEquals("   ", optionalStringAllowingEmpty(JSONObject().put("note", "   "), "note"))
        assertEquals("42", optionalStringAllowingEmpty(JSONObject().put("note", 42), "note"))
    }

    @Test
    fun `only note and nickname use the lenient reader`() {
        val code = ProductionSource.commentsRemoved("PiqRouter.kt")
        assertEquals(
            "昵称更新必须走空串直通",
            1,
            Regex("""nickname\s*=\s*optionalStringAllowingEmpty\(body,\s*"nickname"\)""").findAll(code).count(),
        )
        assertEquals(
            "周期备注更新必须走空串直通",
            1,
            Regex("""note\s*=\s*optionalStringAllowingEmpty\(body,\s*"note"\)""").findAll(code).count(),
        )
        assertEquals(
            "宽松读数只该有两个调用点（加上声明共三个命中）",
            3,
            Regex("""optionalStringAllowingEmpty\(""").findAll(code).count(),
        )
    }

    @Test
    fun `registration and login still reject empty credentials`() {
        val code = ProductionSource.commentsRemoved("PiqRouter.kt")
        listOf("private fun register(body: JSONObject): Envelope", "private fun login(body: JSONObject): Envelope")
            .forEach { signature ->
                val body = functionBody(code, signature)
                assertTrue("$signature 必须仍用 requireString 读用户名", body.contains("""requireString(body, "username")"""))
                assertTrue("$signature 必须仍用 requireString 读口令", body.contains("""requireString(body, "password")"""))
                assertTrue("$signature 不得把空串当合法凭证", !body.contains("optionalStringAllowingEmpty"))
            }
    }

    private fun functionBody(code: String, signature: String): String {
        val start = code.indexOf(signature)
        require(start >= 0) { "找不到 $signature，源守卫已失效" }
        val next = Regex("""(?m)^    private fun """).find(code, start + signature.length)?.range?.first
        return code.substring(start, next ?: code.length)
    }
}
