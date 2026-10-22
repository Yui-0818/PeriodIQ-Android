package io.github.yui0818.periodiq

import io.github.yui0818.periodiq.algo.PiqAlgo
import io.github.yui0818.periodiq.data.PiqCrud
import io.github.yui0818.periodiq.data.PiqDb

import android.content.Context
import android.util.Log

/**
 * 业务侧服务装配（R3）。
 *
 * 单独成对象的原因：SQLite 建表与 `Python.start`（导入 `app.imy`）都不该发生在主线程，
 * 而首次触发只发生在**第一次用到业务层的路径**上 ⇒ 由 lazy 属性持有，页面渲染与门控注入本身不建表、不起 Python。
 * ⚠ 但首次触发**不必**在 JS 桥线程：`ApiGatewayHandler`（`AssetRootHandler.kt:153`，`:161` 经 `gateway.request`）跑在资源拦截线程上。
 *
 * 这里**不提供**任何"给探针读表数/读路径"的入口：那些取证走仓库外手段
 * （`adb shell run-as <包名>` 读私有目录里的库文件 + `logcat` 的 `db-open|path=` 行），
 * 生产代码里不留测试钩子（`AGENTS.md` 硬自检 G5）。
 *
 * ⚠ 本对象**可以**每 Activity 一份（`MainActivity` 重建会再来一个），但它下面的库是
 * **进程级单实例**（[PiqDb.shared]）：`PiqDb` 的构造器已私有，这里拿不到第二份句柄，
 * 所以"单写者"不变量跨 Activity 重建仍然成立（F-R10.2-01，R10.3）。
 */
class PiqServices(context: Context, sessions: SessionHolder, versionName: String) {

    private val store = PiqDb.shared(context)
    private val crud = PiqCrud(store)

    val algoGate = AlgoGate(start = { PiqAlgo.ensureStarted(context) })

    val router: PiqRouter = run {
        val algoReady = algoGate.ensure()
        Log.i(TAG, "services-ready|algo=$algoReady|attempts=${algoGate.attempts()}|python=${if (algoReady) PiqAlgo.pythonVersion() else "n/a"}")
        PiqRouter(store, crud, sessions, RateLimiter(), versionName, algoGate)
    }

    /**
     * 会话令牌是否仍在有效期内（L2E-03 的判过期入口）。
     *
     * ⚠ 这里**必须**复用 `PiqCrud.getSessionUser` —— 也就是 `PiqRouter.requireUser` 服务请求时
     * 用的同一条 SQL。判过期若另起一套口径（比如拿落盘时间自己算），就会出现"宿主说有效、
     * 请求说无效"，那正是这条缺陷的形态。
     */
    fun isSessionLive(token: String): Boolean = crud.getSessionUser(token) != null

    companion object {
        private const val TAG = "PIQ"
    }
}
