package io.github.yui0818.periodiq

import io.github.yui0818.periodiq.api.contract.ApiError

/**
 * WebView 文档边界（R13.1 F3）：只有应用自己的 asset origin 可以调用 JS 桥。
 *
 * `startsWith(origin)` 会把 `https://appassets.androidplatform.net.evil` 也放进来，
 * 所以判据必须是“等于 origin，或以 `origin/` 开头”。[currentUrl] 为 null（WebView 尚未
 * 加载任何文档）同样拒绝。
 */
object BridgeOriginGuard {

    const val ORIGIN = "https://appassets.androidplatform.net"

    fun isTrusted(currentUrl: String?, origin: String = ORIGIN): Boolean =
        currentUrl != null && (currentUrl == origin || currentUrl.startsWith("$origin/"))
}

/**
 * 算法可用性的进程级闸门（R13.1 F10）。
 *
 * 首次构造服务时尝试启动 Python；失败后**只允许再试一次**，之后一律返回 false，
 * 让路由稳定给 503 `ALGO_UNAVAILABLE`，不把 `UninitializedPropertyAccessException`
 * 漏成 500。重试计数是进程级的；进程重启会得到新的一次机会。
 */
class AlgoGate(private val start: () -> Boolean, private val maxAttempts: Int = MAX_ATTEMPTS) {

    private var attempts = 0

    @Volatile
    private var ready = false

    @Synchronized
    fun ensure(): Boolean {
        if (ready) return true
        if (attempts >= maxAttempts) return false
        attempts += 1
        ready = runCatching { start() }.getOrDefault(false)
        return ready
    }

    /** 已尝试次数（测试与日志读它，不暴露启动异常本身）。 */
    fun attempts(): Int = attempts

    companion object {
        const val MAX_ATTEMPTS = 2
    }
}

/**
 * 内存滑动窗口限流（桌面 `routes/auth.py` 的 F2 / EN-1 移植）。
 *
 * 桌面的键：登录按用户名、注册按客户端 IP；安卓没有"客户端 IP"这一概念
 * （本机直连、无 HTTP 层），注册的键取固定值 [REGISTER_KEY] —— 语义等价于桌面自己
 * 登记的"本地单机应用下实际就是全局注册频率上限"（`auth.py::_client_key` 的局限说明）。
 */
class RateLimiter(private val clock: () -> Double = { System.nanoTime() / 1_000_000_000.0 }) {

    private val loginFailures = mutableMapOf<String, MutableList<Double>>()
    private val loginLockedUntil = mutableMapOf<String, Double>()
    private val registerFailures = mutableMapOf<String, MutableList<Double>>()
    private val registerLockedUntil = mutableMapOf<String, Double>()

    /** 锁未到期 → 抛 429（带可读剩余秒数）；到期 → 解禁并清该键状态。 */
    fun checkLogin(username: String) = checkLocked(loginLockedUntil, loginFailures, username, clock())

    /** 注册侧的同一判断，但整台设备共用一个键位（桌面即按 IP/全局计，不区分用户名）。 */
    fun checkRegister() =
        checkLocked(registerLockedUntil, registerFailures, REGISTER_KEY, clock())

    /** 记一次登录失败：落在窗口内累计到 [PiqConfig.LOGIN_MAX_FAILURES] 即锁 [PiqConfig.LOGIN_LOCK_SECONDS] 秒。 */
    fun recordLoginFailure(username: String) =
        recordFailure(loginFailures, loginLockedUntil, username, clock(), PiqConfig.LOGIN_WINDOW_SECONDS, PiqConfig.LOGIN_MAX_FAILURES, PiqConfig.LOGIN_LOCK_SECONDS)

    /** 记一次注册失败（含用户名占用）：阈值与锁定时长取 `REGISTER_*` 一组，键位是设备级的 [REGISTER_KEY]。 */
    fun recordRegisterFailure() =
        recordFailure(registerFailures, registerLockedUntil, REGISTER_KEY, clock(), PiqConfig.REGISTER_WINDOW_SECONDS, PiqConfig.REGISTER_MAX_FAILURES, PiqConfig.REGISTER_LOCK_SECONDS)

    /** 登录成功后清空该用户名的失败计数与锁定状态（不让历史失败拖住后面的正常登录）。 */
    fun clearLogin(username: String) {
        loginFailures.remove(username)
        loginLockedUntil.remove(username)
    }

    /** 注册成功后清空设备级的注册失败计数。 */
    fun clearRegister() {
        registerFailures.remove(REGISTER_KEY)
        registerLockedUntil.remove(REGISTER_KEY)
    }

    private fun checkLocked(
        locked: MutableMap<String, Double>,
        failures: MutableMap<String, MutableList<Double>>,
        key: String,
        now: Double,
    ) {
        val until = locked[key] ?: return
        if (now < until) {
            val retryAfter = maxOf(1, (until - now).toInt() + 1)
            throw ApiError(429, "尝试次数过多，请 $retryAfter 秒后再试", "TOO_MANY_ATTEMPTS")
        }
        locked.remove(key)
        failures.remove(key)
    }

    private fun recordFailure(
        failures: MutableMap<String, MutableList<Double>>,
        locked: MutableMap<String, Double>,
        key: String,
        now: Double,
        windowSeconds: Double,
        maxFailures: Int,
        lockSeconds: Double,
    ) {
        val cutoff = now - windowSeconds
        val kept = failures.getOrPut(key) { mutableListOf() }.filterTo(mutableListOf()) { it > cutoff }
        kept.add(now)
        failures[key] = kept
        if (kept.size >= maxFailures) {
            locked[key] = now + lockSeconds
            failures.remove(key)
        }
    }

    companion object {
        const val REGISTER_KEY = "local"
    }
}

/**
 * 凭证存储的抽象（生产实现走 Android Keystore；单测用内存假实现，不留测试钩子在生产代码里）。
 *
 * `save` 返回**是否真的落盘**：Keystore 不可用时生产实现会失败，调用方必须能把这个失败
 * 告诉用户（R8 L3 的 P-08：此前 `setSetting` 无条件回 `ok=true`，UI 显示成功而凭证根本没存）。
 */
interface CredentialStore {
    fun enabled(): Boolean
    fun save(token: String): Boolean
    fun load(): String?

    /** 撤销落盘凭证；实现**必须**同时把自动登录开关回弹成关闭（不留"开关开着但没有凭证"的状态）。 */
    fun clear()
}

/**
 * 启动期候选凭证的 TTL 结算结果（L2E-03）。
 *
 * `NONE` = 此刻没有待结算的候选（从未开自动登录、或结算已经做过）；
 * `KEPT` = 会话行仍在有效期内，采用的会话原样留下；
 * `EXPIRED` = 会话行已过期/已不在，候选**不被采用**。
 */
enum class SessionSettlement { NONE, KEPT, EXPIRED }

/**
 * 宿主令牌的进程级槽（R13.1 F1 / P1-01）。
 *
 * Activity 会因字号/密度/语言等配置变更而重建；令牌若只挂在 Activity 私有的
 * [SessionHolder] 字段上，重建后的新实例就看不到刚登录的会话，首条 `/auth/me` 会被
 * 自己判成 401。槽的生命周期跟进程走，Activity 重建只换持有者、不换令牌。
 *
 * **不写 `Bundle`/`onSaveInstanceState`**：令牌不进入任何可被系统序列化的状态
 * （提示词 §4.1-F1）。进程结束 = 槽消失；需要跨进程的自动登录仍走 [CredentialStore]。
 */
class SessionSlot {

    @Volatile
    var token: String? = null

    companion object {
        /** 生产 Activity 共用的那一份；JVM 测试用独立实例，避免用例之间串值。 */
        val process = SessionSlot()
    }
}

/**
 * 当前会话持有者。
 *
 * 桌面用 `Authorization: Bearer <token>` 请求头（HTTP 层）；桥的签名是
 * `request(method, path, body)`（契约 §5 钉死），没有请求头通道 ⇒ 令牌由宿主持有：
 * 登录/注册成功时登记，登出/删号时清除。自动登录开启时（[CredentialStore.enabled]）
 * 把同一令牌交给加密存储，下次启动在有效期内重新采用。
 *
 * [slot] 默认每个实例一份（JVM 测试的隔离语义）；生产 `MainActivity` 必须传
 * [SessionSlot.process]，这样 Activity 重建后的新实例仍读到同一枚令牌（R13.1 F1）。
 *
 * ⚠ **令牌文本不进日志、不进备份、不进导出**（`AGENTS.md` §5、契约 §7.2）。
 */
class SessionHolder(
    private val credentials: CredentialStore? = null,
    private val slot: SessionSlot = SessionSlot(),
) {

    /**
     * 启动期从加密存储里解出来的**候选**令牌。
     *
     * 只有 [adoptPersisted] 会置位（登录/注册走 [issue]，那里的令牌是数据库刚刚发出的，
     * 不存在"过期还留着"的窗口）。置位之后、[settleAdoption] 结算之前，这条会话只是候选：
     * 宿主不得在它上面服务任何一次请求（L2E-03 的硬约束）。
     */
    @Volatile
    private var candidateToken: String? = null

    private val settleLock = Any()

    fun current(): String? = slot.token

    fun adopt(newToken: String) {
        slot.token = newToken
    }

    /** 登录/注册成功：采用 + （开关开启时）落盘。 */
    fun issue(newToken: String) {
        adopt(newToken)
        if (credentials?.enabled() == true) credentials.save(newToken)
    }

    /**
     * 进程启动时尝试采用持久化凭证：仅在开关开启且凭证能解出时生效。
     * 解不出 / 已被撤销 ⇒ 返回 false，调用方按"需要登录"继续（不报错、不清开关）。
     *
     * ⚠ 返回 true 只代表"凭证解出来了"，**不代表会话还有效** —— 加密存储里没有有效期信息，
     * 有效性只有数据库知道。所以这里只登记候选，由 [settleAdoption] 在首条请求前结算。
     */
    fun adoptPersisted(): Boolean {
        val stored = credentials?.takeIf { it.enabled() }?.load() ?: return false
        adopt(stored)
        candidateToken = stored
        return true
    }

    /** 是否正处于"已采用候选、尚未结算 TTL"的窗口内。 */
    fun adoptionPending(): Boolean = candidateToken != null

    /**
     * 在首条请求被服务之前结算候选（L2E-03）。
     *
     * `isLive` 由宿主注入，**必须是 `PiqCrud.getSessionUser` 那一条 SQL**
     * （`expires_at IS NULL OR expires_at > datetime('now')`）—— 判过期用第二条口径
     * 就会与 `requireUser` 分出两种答案，那正是这条缺陷的成因。
     * 过期 ⇒ [clear]：内存令牌撤回 + 落盘凭证清除 + 自动登录开关回弹，一条腿走完。
     *
     * 幂等且线程安全：网关会被 JS 桥线程与资源拦截线程同时进入，结算只做一次，
     * 后进者拿到 [SessionSettlement.NONE] 直接放行（此时候选已被处理完）。
     */
    fun settleAdoption(isLive: (String) -> Boolean): SessionSettlement = synchronized(settleLock) {
        val candidate = candidateToken ?: return SessionSettlement.NONE
        candidateToken = null
        if (isLive(candidate)) return SessionSettlement.KEPT
        if (slot.token == candidate) clear()
        SessionSettlement.EXPIRED
    }

    /** 登出 / 删号 / 凭证失效：内存与落盘一起清。 */
    fun clear() {
        slot.token = null
        credentials?.clear()
    }
}
