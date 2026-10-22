package io.github.yui0818.periodiq

import io.github.yui0818.periodiq.data.PiqCrud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * 删除账号的**覆盖面**哨兵（R9.3 追加）。
 *
 * 为什么要它：D3 的设备判据"六表按 user 全 0"在模拟器上始终取不到读数（本轮设备异常 10 次），
 * 而这一半**不依赖设备**——`deleteUserAccount` 就是把 [PiqCrud] 里那组 DELETE 语句逐条执行。
 * 语句集合被钉住之后，"少删一张表"这类缺陷就有一个会红的地方，不必等那台说没就没的模拟器。
 *
 * 取值方式：那组语句在生产代码里是 `private val`（在伴生对象内），公开 seam 不存在；
 * 本测试**不修改生产代码**，用反射把伴生对象里的 `List<String>` 取出来。
 * 这样做的代价是"字段改名会让本测试取不到值"——所以第一个用例先断言"确实取到了 6 条"，
 * 取不到就报红，而不是静默跳过。
 */
class AccountDeleteCoverageTest {

    private fun companionLists(): List<List<*>> {
        val out = mutableListOf<List<*>>()
        val outer = PiqCrud::class.java
        for (f in outer.declaredFields) {
            if (!Modifier.isStatic(f.modifiers)) continue        // 实例字段 get(null) 会 NPE
            f.isAccessible = true
            (f.get(null) as? List<*>)?.let { out.add(it) }
        }
        val companionField = outer.declaredFields.firstOrNull { it.name == "Companion" }
        if (companionField != null) {
            companionField.isAccessible = true
            val comp = companionField.get(null)
            for (f in comp.javaClass.declaredFields) {
                if (Modifier.isStatic(f.modifiers)) continue      // 伴生对象里取实例字段
                f.isAccessible = true
                (f.get(comp) as? List<*>)?.let { out.add(it) }
            }
        }
        return out
    }

    private fun deleteStatements(): List<String> {
        val cands = companionLists().filter { ls -> ls.filterIsInstance<String>().any { it.startsWith("DELETE FROM cycles") } }
        assertEquals("反射应当只命中一组『删除账号』语句列表", 1, cands.size)
        return cands.single().filterIsInstance<String>()
    }

    private fun tablesOf(sql: String): String =
        sql.removePrefix("DELETE FROM ").substringBefore(' ').trim()

    @Test
    fun `delete account statement set covers exactly the six tables`() {
        val sqls = deleteStatements()
        assertEquals("语句条数（少一张表就是数据残留）", 6, sqls.size)
        assertEquals(
            setOf("cycles", "predictions", "model_params", "app_settings", "sessions", "users"),
            sqls.map(::tablesOf).toSet()
        )
    }

    @Test
    fun `five of six scopes are by user id and the account row is by its own id`() {
        val sqls = deleteStatements()
        val byUser = sqls.filter { it.endsWith("WHERE user_id = ?") }
        assertEquals("按 user_id 过滤的语句应有 5 条", 5, byUser.size)
        assertEquals(listOf("users"), sqls.filterNot { it.endsWith("WHERE user_id = ?") }.map(::tablesOf))
        assertEquals("账号行必须最后删，否则外键先断", "users", tablesOf(sqls.last()))
    }

    @Test
    fun `pruning statements are not mistaken for account deletion`() {
        val sqls = deleteStatements()
        assertFalse("过期清理语句不该混进删号集合", sqls.any { it.contains("expires_at") })
        assertFalse("保留最新 N 条的清理语句不该混进删号集合", sqls.any { it.contains("LIMIT") })
    }

    /**
     * 阴性对照（§5.4 第 2 道闸门）：**判据必须能因缺陷而变红**。
     * 这里不是改生产代码，而是构造"少一张表"与"多一张无关表"两种列表过同一条判据，
     * 要求两者都被拒——否则"集合相等"就是一条橡皮筋。
     */
    @Test
    fun `the coverage assertion rejects both a missing table and a wrong extra table`() {
        val sqls = deleteStatements()
        val expected = setOf("cycles", "predictions", "model_params", "app_settings", "sessions", "users")
        val missingOne = sqls.filterNot { tablesOf(it) == "model_params" }
        val wrongExtra = sqls + "DELETE FROM audit_log WHERE user_id = ?"
        assertEquals("少删一张表 ⇒ 覆盖集必须变小（判据会红）", 5, missingOne.map(::tablesOf).toSet().size)
        assertFalse("少删一张表 ⇒ 与期望集合不再相等", missingOne.map(::tablesOf).toSet() == expected)
        assertFalse("多一张不存在的表 ⇒ 与期望集合不再相等", wrongExtra.map(::tablesOf).toSet() == expected)
        assertEquals("多一张表 ⇒ 『按 user_id 过滤 = 5 条』这条也会红", 6, wrongExtra.count { it.endsWith("WHERE user_id = ?") })
    }
}
