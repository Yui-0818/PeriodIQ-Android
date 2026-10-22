package io.github.yui0818.periodiq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R13.2 B1：路由清单特征测试。
 *
 * 口径：`route()` 的 `when` 内 **8 空格缩进**的 `method ==` 是路由守卫；
 * `dispatch()` 里 12 空格缩进的 `method == "POST" && rest == REST_IMPORT` 是请求体解析条件，
 * **不是**路由守卫。全文件原始 `method ==` = 32 = 31 条守卫 + 1 条解析条件。
 */
class ApiRouteInventoryTest {

    private val source = ProductionSource.commentsRemoved("PiqRouter.kt")
    private val routeBody = ProductionSource.functionBody("PiqRouter.kt", "private fun route(")
    private val dispatchBody = ProductionSource.functionBody("PiqRouter.kt", "private fun dispatch(")

    @Test
    fun `route table has thirty concrete paths plus the debug wildcard`() {
        val guards = routeBody.lines().filter { it.startsWith("        method == ") }
        assertEquals("路由守卫必须是 31 条", 31, guards.size)

        val routes = guards.map(::routeOf).toSet()
        assertEquals("路由守卫不得重复", guards.size, routes.size)
        assertEquals("路由清单（30 具体 + /debug/* 通配）", expectedRoutes, routes)
        assertEquals("具体端点必须是 30 条", 30, routes.count { it != "GET /debug/*" })
        assertEquals("通配必须只出现一次", 1, routes.count { it == "GET /debug/*" })
    }

    @Test
    fun `else branch stays a 404 not found`() {
        assertTrue(
            "route() 必须保留 else 404",
            routeBody.contains("""else -> throw ApiError(404, MSG_NOT_FOUND, "NOT_FOUND")"""),
        )
        assertTrue("404 之前的守卫不得混入 else", routeBody.lines().filter { it.startsWith("        method == ") }.none { it.startsWith("        else") })
    }

    @Test
    fun `raw method comparisons are thirty one guards plus one import body condition`() {
        val raw = Regex("""\bmethod\s*==\s*""").findAll(source).count()
        assertEquals("全文件原始 method == 必须是 32", 32, raw)
        val dispatchConditions = dispatchBody.lines().filter { it.contains("method ==") }
        assertEquals("dispatch 里只能有 1 条请求体条件", 1, dispatchConditions.size)
        assertTrue(
            "被排除的那条必须是 POST import 的请求体解析条件",
            dispatchConditions.single().contains("REST_IMPORT") && dispatchConditions.single().contains("ImportBodyGate.parse"),
        )
        assertEquals("31 条守卫 + 1 条解析条件", raw - 1, routeBody.lines().count { it.startsWith("        method == ") })
    }

    private fun routeOf(line: String): String {
        val method = Regex("""method == "([A-Z]+)"""").find(line)?.groupValues?.get(1)
            ?: throw AssertionError("路由行缺少 method：$line")
        val path = when {
            line.contains("CYCLE_ID.matches(rest)") -> "/cycles/{id}"
            line.contains("""rest.startsWith("/debug/")""") -> "/debug/*"
            else -> Regex("""rest == "([^"]+)"""").find(line)?.groupValues?.get(1)
                ?: throw AssertionError("路由行缺少 path：$line")
        }
        return "$method $path"
    }

    private companion object {
        val expectedRoutes: Set<String> = setOf(
            "GET /health",
            "POST /auth/register",
            "POST /auth/login",
            "GET /auth/me",
            "POST /auth/logout",
            "POST /auth/change-password",
            "POST /auth/delete-account",
            "PUT /auth/profile",
            "GET /cycles",
            "POST /cycles",
            "PUT /cycles/{id}",
            "DELETE /cycles/{id}",
            "GET /predictions/current",
            "GET /predictions/history",
            "GET /analysis/accuracy",
            "GET /analysis/cycle-stats",
            "GET /analysis/fertile-window",
            "GET /settings",
            "PUT /settings",
            "GET /export",
            "GET /export/preview",
            "POST /import",
            "POST /import/pdf",
            "GET /backups",
            "PUT /backups/dir",
            "GET /debug/model",
            "GET /debug/model_history",
            "GET /debug/prediction_trace",
            "GET /keepalive",
            "GET /keepalive/status",
            "GET /debug/*",
        )
    }
}
