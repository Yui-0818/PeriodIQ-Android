package io.github.yui0818.periodiq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话槽的生产接线与 Bundle 纪律（R13.1 F1 / R12 的 P1-01）。
 *
 * 行为面（新 `SessionHolder` 仍看到同一枚令牌、登出双向清除）由 [SessionHolderTest] 的
 * `process scoped slot survives holder recreation` 覆盖；这里钉的是**生产 Activity 真的接了这根线**，
 * 以及“令牌不写进 `onSaveInstanceState`”。红路径：把 `SessionSlot.process` 换回默认槽 ⇒ 第 1 条红；
 * 新增 `onSaveInstanceState` 并 `putString(...token...)` ⇒ 第 3 条红。
 */
class SessionWiringTest {

    private val activity: String = ProductionSource.code("MainActivity.kt")

    @Test
    fun `activity rebuilds the holder on the process wide slot`() {
        assertEquals(
            "MainActivity 必须把进程级槽交给 SessionHolder",
            1,
            Regex("""SessionHolder\(settings,\s*SessionSlot\.process\)""").findAll(activity).count(),
        )
        val allProduction = listOf("MainActivity.kt", "PiqSettings.kt", "PiqServices.kt")
            .joinToString("\n") { ProductionSource.code(it) }
        assertEquals(
            "除 Activity 外不得再建第二个持有者（否则重建时可能各拿各的槽）",
            1,
            Regex("""SessionHolder\(""").findAll(allProduction).count(),
        )
    }

    @Test
    fun `slot is a companion object owned by the process`() {
        val core = ProductionSource.code("PiqApiCore.kt")
        assertTrue(
            "SessionSlot 的进程实例必须唯一且可被 Activity 取到",
            Regex("""companion\s+object\s*\{\s*val\s+process\s*=\s*SessionSlot\(\)""").containsMatchIn(core),
        )
    }

    @Test
    fun `the session token is never written into a saved state bundle`() {
        val overrides = Regex("""onSaveInstanceState""").findAll(activity).count()
        assertEquals("MainActivity 不得自己保存状态（令牌只能活在进程槽/Kotlin 字段里）", 0, overrides)
        assertFalse("生产代码不得把 token 放进 Bundle", Regex("""putString\([^)]*token""", RegexOption.IGNORE_CASE).containsMatchIn(activity))
        assertFalse("生产代码不得把 token 放进 Intent extra", Regex("""putExtra\([^)]*token""", RegexOption.IGNORE_CASE).containsMatchIn(activity))
    }
}
