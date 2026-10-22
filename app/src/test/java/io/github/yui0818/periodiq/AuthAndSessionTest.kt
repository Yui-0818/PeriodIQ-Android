package io.github.yui0818.periodiq

import io.github.yui0818.periodiq.api.contract.ApiError

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 登录/注册限流与会话采用逻辑哨兵（R3 F2 / F5）。
 *
 * 时钟是注入的（[RateLimiter] 的 `clock` 参数），因此"锁定期内一律 429 / 到期自动解禁"
 * 这两条都能被确定性地测到，不用 sleep 赌时序（`AGENTS.md` §3.4 第 3 道闸门）。
 */
class RateLimiterTest {

    private var now = 1_000.0
    private val limiter = RateLimiter { now }

    @Test
    fun `login locks after five failures inside the window and clears after lock expires`() {
        repeat(4) { limiter.recordLoginFailure("user"); limiter.checkLogin("user") }
        limiter.recordLoginFailure("user")
        expect429OnLogin()

        now += PiqConfig.LOGIN_LOCK_SECONDS - 1
        expect429OnLogin()

        now += 2
        limiter.checkLogin("user") // 到期解禁：不得抛
    }

    @Test
    fun `successful login clears the counter`() {
        repeat(4) { limiter.recordLoginFailure("user"); limiter.checkLogin("user") }
        limiter.clearLogin("user")
        limiter.checkLogin("user")
        limiter.recordLoginFailure("user")
        limiter.checkLogin("user") // 清零后重新计数，第一次失败不该触发锁定
    }

    @Test
    fun `failures older than the window are dropped`() {
        repeat(4) { limiter.recordLoginFailure("user") }
        now += PiqConfig.LOGIN_WINDOW_SECONDS + 1
        limiter.recordLoginFailure("user")
        limiter.checkLogin("user") // 窗口外的四次失败必须已失效
    }

    @Test
    fun `register uses its own thresholds and a single local key`() {
        repeat(PiqConfig.REGISTER_MAX_FAILURES - 1) { limiter.recordRegisterFailure() }
        limiter.checkRegister()
        limiter.recordRegisterFailure()
        val err = runCatching { limiter.checkRegister() }.exceptionOrNull()
        assertTrue("注册达阈值后必须 429", err is ApiError && err.status == 429 && err.code == "TOO_MANY_ATTEMPTS")
        limiter.clearRegister()
        limiter.checkRegister()
    }

    /** 内存态限流不区分用户来源：桌面注册按 IP，本机按"全局一个键"；注册那一桶也不替登录解锁。 */
    @Test
    fun `register key is global on device`() {
        limiter.clearRegister()
        limiter.checkRegister()                       // 起始态：谁都能注册
        var attempts = 0
        var blocked = false
        while (attempts < 20 && !blocked) {           // 不写死阈值：写死等于把常量抄一遍
            limiter.recordRegisterFailure()
            attempts++
            val err = runCatching { limiter.checkRegister() }.exceptionOrNull()
            blocked = err is ApiError && err.status == 429
        }
        assertTrue("连记注册失败必须在 20 次之内锁住全局注册口，实测=$attempts 次", blocked)
        limiter.checkLogin("someone-else")            // 注册被锁 ≠ 某个用户名的登录被锁（登录键按用户分）
        limiter.clearRegister()
        limiter.checkRegister()                       // 清理后恢复
    }

    private fun expect429OnLogin() {
        val err = runCatching { limiter.checkLogin("user") }.exceptionOrNull()
        assertTrue("锁定期内必须 429 TOO_MANY_ATTEMPTS，实测=$err", err is ApiError && err.status == 429)
        assertEquals("TOO_MANY_ATTEMPTS", (err as ApiError).code)
    }
}

/**
 * 自动登录的宿主机侧语义（R3 F5）：
 * 开关关 ⇒ 启动不采用任何凭证；开关开且凭证在 ⇒ 采用；凭证解不出 ⇒ 静默清掉、不采用。
 *
 * 这里用内存假存储注入（[CredentialStore] 的实现之一），生产路径用的是 Android Keystore；
 * 假实现只存在于测试源码集 ⇒ 不构成生产代码里的测试钩子（G5）。
 */
class SessionHolderTest {

    private class FakeStore : CredentialStore {
        var enabled = false
        var saved: String? = null
        var decryptFails = false

        /** 模拟 Keystore 不可用：`save` 必须能把失败告诉调用方（R8 L3 的 P-08）。 */
        var saveWorks = true
        var saveCalls = 0
        override fun enabled(): Boolean = enabled
        override fun save(token: String): Boolean {
            saveCalls += 1
            if (!saveWorks) return false
            saved = token
            return true
        }
        override fun load(): String? = if (decryptFails) null else saved
        override fun clear() { saved = null }
    }

    @Test
    fun `issue persists only when the switch is on`() {
        val store = FakeStore()
        val sessions = SessionHolder(store)
        sessions.issue("token-a")
        assertEquals("关着开关不得落盘凭证", null, store.saved)

        store.enabled = true
        val second = SessionHolder(store)
        second.issue("token-b")
        assertEquals("token-b", store.saved)
    }

    @Test
    fun `issue still yields an in-memory session when persisting fails`() {
        val store = FakeStore().apply { enabled = true; saveWorks = false }
        val sessions = SessionHolder(store)
        sessions.issue("token-c")
        // 两条断言都只钉 `SessionHolder` 这个被测对象的可观测量：
        //   ① 落盘失败**不能**牵连内存会话（否则用户当场被踢回登录页）；
        //   ② 必须真的尝试过一次持久化（静默跳过就等于把失败藏起来）。
        // 原先这里还有一条 `store.saved == null`，那是 `FakeStore` 自己分支的写法决定结果的
        // 同义反复（R8 的 V-3 已由 L3 用变异实测证明），已删。
        assertEquals("落盘失败也不得牵连内存会话", "token-c", sessions.current())
        assertEquals("失败必须被真实地尝试过一次持久化所暴露", 1, store.saveCalls)
    }

    @Test
    fun `startup adopts persisted credential only when enabled`() {
        val store = FakeStore().apply { saved = "token-x" }
        val off = SessionHolder(store)
        assertFalse("开关关闭时绝不能采用旧凭证", off.adoptPersisted())
        assertFalse("并且不产生任何会话", off.current() != null)

        store.enabled = true
        val on = SessionHolder(store)
        assertTrue(on.adoptPersisted())
        assertEquals("token-x", on.current())
    }

    @Test
    fun `unreadable credential degrades silently to no session`() {
        val store = FakeStore().apply { enabled = true; saved = "token-y"; decryptFails = true }
        val sessions = SessionHolder(store)
        assertFalse("解不出凭证 ⇒ 不采用（由前端 401 路径回登录页）", sessions.adoptPersisted())
        assertEquals(null, sessions.current())
    }

    @Test
    fun `clear wipes both memory and storage`() {
        val store = FakeStore().apply { enabled = true }
        val sessions = SessionHolder(store)
        sessions.issue("token-z")
        sessions.clear()
        assertEquals(null, sessions.current())
        assertEquals(null, store.saved)
    }

    /**
     * R13.1 F1 / P1-01：Activity 重建会新建一个 SessionHolder，但进程级槽必须把令牌交给
     * 新实例；登出在新实例上执行也要能清掉旧实例看到的令牌。
     */
    @Test
    fun `process scoped slot survives holder recreation and clears both holders`() {
        val store = FakeStore()
        val slot = SessionSlot()
        val first = SessionHolder(store, slot)
        first.issue("token-shared")

        val second = SessionHolder(store, slot)
        assertEquals("新 Activity 必须仍看到同一枚会话令牌", "token-shared", second.current())

        second.clear()
        assertEquals("新实例登出后旧实例也不得再看到令牌", null, first.current())
    }

    /** 默认构造的测试实例各自独立，避免 JVM 用例之间串值。 */
    @Test
    fun `default holders do not share a slot`() {
        val first = SessionHolder(FakeStore())
        val second = SessionHolder(FakeStore())
        first.issue("token-isolated")
        assertEquals(null, second.current())
    }
}

/**
 * L2E-03 哨兵：启动期从加密存储解出来的凭证只是**候选**，必须在首条请求被服务之前
 * 按与 `PiqCrud.getSessionUser` 同一条口径结算掉。
 *
 * 被测对象是 `SessionHolder`，所以这里只注入两样东西：内存假存储（凭证从哪来）与
 * `isLive` lambda（数据库说这枚令牌还活着吗）。真实口径由设备腿读 logcat 证（R10 报告 §3）。
 */
class StartupSessionTtlTest {

    private class FakeStore(var enabled: Boolean = false, var saved: String? = null) : CredentialStore {
        var clearCalls = 0
        override fun enabled(): Boolean = enabled
        override fun save(token: String): Boolean { saved = token; return true }
        override fun load(): String? = saved
        override fun clear() { saved = null; clearCalls += 1 }
    }

    @Test
    fun `expired candidate is not adopted and its credential is revoked`() {
        val store = FakeStore(enabled = true, saved = "token-old")
        val sessions = SessionHolder(store)
        assertTrue("开关开着、凭证解得出来 ⇒ 先登记为候选", sessions.adoptPersisted())
        assertTrue("候选在结算前必须处于待结算态", sessions.adoptionPending())

        val outcome = sessions.settleAdoption { false }

        assertEquals(SessionSettlement.EXPIRED, outcome)
        assertEquals("过期凭证不得留在内存会话里", null, sessions.current())
        assertEquals("落盘凭证必须一起撤销", null, store.saved)
        assertEquals("撤销只走一次", 1, store.clearCalls)
        assertFalse("结算已做完，不得留下待结算态", sessions.adoptionPending())
    }

    @Test
    fun `live candidate keeps its session and its credential`() {
        val store = FakeStore(enabled = true, saved = "token-new")
        val sessions = SessionHolder(store)
        sessions.adoptPersisted()

        val outcome = sessions.settleAdoption { true }

        assertEquals(SessionSettlement.KEPT, outcome)
        assertEquals("token-new", sessions.current())
        assertEquals("有效的会话不该动落盘凭证", "token-new", store.saved)
        assertEquals(0, store.clearCalls)
    }

    @Test
    fun `settlement probes the database exactly once`() {
        val store = FakeStore(enabled = true, saved = "token-once")
        val sessions = SessionHolder(store)
        sessions.adoptPersisted()
        var probes = 0
        val isLive: (String) -> Boolean = { probes += 1; true }

        assertEquals(SessionSettlement.KEPT, sessions.settleAdoption(isLive))
        assertEquals(SessionSettlement.NONE, sessions.settleAdoption(isLive))
        assertEquals("结算只做一次；之后的请求不该再查库", 1, probes)
    }

    /**
     * 阴性对照：登录/注册拿到的令牌**不是**候选。
     * 若这条被写坏（比如把 `issue` 也登记成候选），刚登录的会话会在首条请求前被判过期。
     */
    @Test
    fun `a session issued by login is never settled away`() {
        val store = FakeStore(enabled = true)
        val sessions = SessionHolder(store)
        sessions.issue("token-login")
        assertFalse(sessions.adoptionPending())

        assertEquals(SessionSettlement.NONE, sessions.settleAdoption { false })
        assertEquals("token-login", sessions.current())
        assertEquals("刚登录的会话不该被撤销", "token-login", store.saved)
        assertEquals(0, store.clearCalls)
    }
}
