package io.github.yui0818.periodiq

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 重排钩子的判定哨兵（R11 §4："重排钩子一处不许漏"；R11.1 §4.1 P3-1 / P3-2 收窄）。
 *
 * 七条腿里有四条表现为"某条 REST 请求被服务过"，都从 [PiqApiGateway] 这一个漏斗走：
 * 登录 / 首屏取预测 / 周期增删改 / 导入。剩下三条（设置变更、开机、时区与改表）不经过这里，
 * 分别由 `HostSettingsBridge` 与 [PiqReminderReceiver] 负责，设备侧各有独立判据。
 *
 * **会判红的规则**（改回旧行为就红，不是恒真断言）：
 *  - 失败响应不重排：把 [PiqReminderRoutes.action] 里的 `status >= 400` 守卫删掉，
 *    [a failed write does not reschedule] 立刻红；
 *  - 按 id 读周期不重排：把 `method != "GET"` 那一条放宽，
 *    [reading one cycle by id does not reschedule] 立刻红；
 *  - 改密不撤提醒（R11.1 P3-1）：把 `/auth/change-password` 放回 `CANCEL_ALL_REST`，
 *    [changing the password keeps the reminders armed] 立刻红；
 *  - 会话失效要撤提醒（R11.1 P3-2）：把 `status == 401` 的分支删掉，
 *    [an expired session cancels everything] 立刻红；
 *  - 输错密码不撤提醒（R11.1 P3-2）：把 401 一律记成 `CANCEL_ALL`，
 *    [wrong credentials do not cancel the reminders] 立刻红；
 *  - 前缀外与 `/api/v1` 本身的 401 不撤：把 `startsWith("$PREFIX/")` 放宽成 `startsWith(PREFIX)`，
 *    [401 outside the trailing-slash api prefix does not cancel] 立刻红。
 */
class PiqReminderRoutesTest {

    private fun action(method: String, path: String, status: Int = 200) =
        PiqReminderRoutes.action(method, path, status)

    @Test
    fun `the four data-changing requests reschedule`() {
        assertEquals(ReminderAction.RESCHEDULE, action("POST", "/api/v1/auth/login"))
        assertEquals(ReminderAction.RESCHEDULE, action("GET", "/api/v1/predictions/current"))
        assertEquals(ReminderAction.RESCHEDULE, action("POST", "/api/v1/cycles"))
        assertEquals(ReminderAction.RESCHEDULE, action("POST", "/api/v1/import"))
        assertEquals(ReminderAction.RESCHEDULE, action("POST", "/api/v1/auth/register"))
    }

    @Test
    fun `cycle edits addressed by id reschedule but a plain list read also does`() {
        assertEquals(ReminderAction.RESCHEDULE, action("PUT", "/api/v1/cycles/7"))
        assertEquals(ReminderAction.RESCHEDULE, action("DELETE", "/api/v1/cycles/7"))
        assertEquals(ReminderAction.RESCHEDULE, action("GET", "/api/v1/cycles"))
        assertEquals(ReminderAction.RESCHEDULE, action("GET", "/api/v1/auth/me"))
    }

    @Test
    fun `reading one cycle by id does not reschedule`() {
        assertEquals(ReminderAction.NONE, action("GET", "/api/v1/cycles/7"))
    }

    @Test
    fun `logout and account deletion cancel everything`() {
        listOf(
            Triple("POST", "/api/v1/auth/logout", 200),
            Triple("POST", "/api/v1/auth/delete-account", 200),
        ).forEach { (method, path, status) ->
            assertEquals("$method $path", ReminderAction.CANCEL_ALL, action(method, path, status))
        }
    }

    @Test
    fun `changing the password keeps the reminders armed`() {
        assertEquals(ReminderAction.NONE, action("POST", "/api/v1/auth/change-password", 200))
    }

    @Test
    fun `an expired session cancels everything`() {
        assertEquals(ReminderAction.CANCEL_ALL, action("GET", "/api/v1/auth/me", 401))
        assertEquals(ReminderAction.CANCEL_ALL, action("GET", "/api/v1/cycles", 401))
        assertEquals(ReminderAction.CANCEL_ALL, action("POST", "/api/v1/auth/logout", 401))
    }

    @Test
    fun `wrong credentials do not cancel the reminders`() {
        assertEquals(ReminderAction.NONE, action("POST", "/api/v1/auth/login", 401))
        assertEquals(ReminderAction.NONE, action("POST", "/api/v1/auth/register", 401))
    }

    @Test
    fun `other error statuses keep the reminders`() {
        assertEquals(ReminderAction.NONE, action("GET", "/api/v1/auth/me", 403))
        assertEquals(ReminderAction.NONE, action("GET", "/api/v1/auth/me", 500))
        assertEquals(ReminderAction.NONE, action("GET", "/api/v1/cycles", 501))
    }

    @Test
    fun `401 outside the trailing-slash api prefix does not cancel`() {
        assertEquals(ReminderAction.NONE, action("GET", "/js/app.js", 401))
        assertEquals(ReminderAction.NONE, action("GET", "/api/v1", 401))
        assertEquals(ReminderAction.NONE, action("GET", "/api/v2/cycles", 401))
    }

    @Test
    fun `query strings are stripped before matching`() {
        assertEquals(ReminderAction.NONE, action("POST", "/api/v1/auth/login?x=1", 401))
        assertEquals(ReminderAction.CANCEL_ALL, action("POST", "/api/v1/auth/logout?x=1", 200))
        assertEquals(ReminderAction.CANCEL_ALL, action("POST", "/api/v1/auth/logout/?x=1", 200))
        assertEquals(ReminderAction.RESCHEDULE, action("POST", "/api/v1/cycles?x=1", 200))
        assertEquals(ReminderAction.RESCHEDULE, action("POST", "/api/v1/import?x=1", 200))
    }

    @Test
    fun `methods are normalized before the cycle write check`() {
        assertEquals(ReminderAction.NONE, action("GET", "/api/v1/cycles/7", 200))
        assertEquals(ReminderAction.NONE, action("get", "/api/v1/cycles/7", 200))
        assertEquals(ReminderAction.RESCHEDULE, action("get", "/api/v1/cycles", 200))
        assertEquals(ReminderAction.CANCEL_ALL, action("post", "/api/v1/auth/logout", 200))
    }

    @Test
    fun `api root and trailing slash login are not endpoints`() {
        assertEquals(ReminderAction.NONE, action("GET", "/api/v1/", 401))
        assertEquals(ReminderAction.NONE, action("GET", "/api/v1/", 200))
        assertEquals(ReminderAction.NONE, action("POST", "/api/v1/auth/login/", 401))
    }

    @Test
    fun `trailing slashes are normalized at most once`() {
        assertEquals(ReminderAction.CANCEL_ALL, action("POST", "/api/v1/auth/logout/", 200))
        assertEquals(ReminderAction.NONE, action("POST", "/api/v1/auth/logout//", 200))
        assertEquals(ReminderAction.RESCHEDULE, action("POST", "/api/v1/cycles/", 200))
        assertEquals(ReminderAction.NONE, action("POST", "/api/v1/cycles//", 200))
        assertEquals(ReminderAction.NONE, action("POST", "/api/v1/cycles/7/", 200))
        assertEquals(ReminderAction.RESCHEDULE, action("POST", "/api/v1/cycles/7", 200))
        assertEquals(ReminderAction.NONE, action("GET", "/api/v1//", 401))
        assertEquals(ReminderAction.NONE, action("GET", "/api/v1//", 200))
        assertEquals(ReminderAction.NONE, action("GET", "/api/v1///", 401))
    }

    @Test
    fun `a failed write does not reschedule`() {
        listOf(400, 404, 500, 501).forEach { status ->
            assertEquals("status=$status", ReminderAction.NONE, action("POST", "/api/v1/cycles", status))
            assertEquals("status=$status", ReminderAction.NONE, action("POST", "/api/v1/auth/logout", status))
        }
    }

    @Test
    fun `paths outside the api prefix and unrelated reads do nothing`() {
        assertEquals(ReminderAction.NONE, action("GET", "/js/app.js"))
        assertEquals(ReminderAction.NONE, action("GET", "/api/v2/cycles"))
        assertEquals(ReminderAction.NONE, action("GET", "/api/v1/analysis/accuracy"))
        assertEquals(ReminderAction.NONE, action("GET", "/api/v1/export"))
        assertEquals(ReminderAction.NONE, action("GET", "/api/v1/predictions/history"))
    }
}
