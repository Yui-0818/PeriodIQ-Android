package io.github.yui0818.periodiq

import io.github.yui0818.periodiq.api.contract.Envelope
import io.github.yui0818.periodiq.api.contract.genericFailure
import io.github.yui0818.periodiq.data.PiqCrud
import io.github.yui0818.periodiq.data.PiqDb

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowInsetsController
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.File
import java.time.LocalDate
import org.json.JSONObject

/**
 * 安全事件日志里的 URL 形态：只留 scheme、host 与长度（R13.1.1 R09）。
 *
 * `data:` 文档的正文、外部链接的 path/query 都可能带用户输入，**不得**进 logcat；
 * 这里只给定位所需的最小信息。scheme 取首冒号前且限制字符集，避免畸形串塞入任意文本。
 */
internal fun safeUrlForLog(url: String?): String {
    if (url == null) return "scheme=-|host=-|len=0"
    val rawScheme = url.substringBefore(':', "")
    val scheme = if (rawScheme.length in 1..16 && rawScheme.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }) {
        rawScheme
    } else {
        "-"
    }
    val host = runCatching { java.net.URI(url).host }.getOrNull() ?: "-"
    return "scheme=$scheme|host=$host|len=${url.length}"
}

/**
 * 每进程随机的桥令牌（R13.1.1 F3 竞态）：只经 document-start 脚本注入 ORIGIN 文档，
 * `data:`/外部文档拿不到；桥方法再校验一次，避免拉回后 `currentPageUrl` 已是 origin
 * 时把旧文档的迟到调用当成本地页面。
 */
private fun newBridgeToken(): String {
    val bytes = ByteArray(16)
    java.security.SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}

/** 进程级令牌持有者：Activity 重建不换令牌。 */
internal object BridgeToken {
    val value: String = newBridgeToken()
}

/** 包装 `PIQNative` / `PIQExport`，给页内调用补上令牌；只对 ORIGIN 生效。 */
private fun bridgeTokenScript(token: String): String = """
    (function () {
        if (window.__piqTokenInstalled) { return; }
        window.__piqTokenInstalled = true;
        var t = "$token";
        var n = window.PIQNative;
        if (n) {
            window.PIQNative = {
                request: function (m, p, b) { return n.requestWithToken(m, p, b, t); },
                getSetting: function (k) { return n.getSettingWithToken(k, t); },
                setSetting: function (k, v) { return n.setSettingWithToken(k, v, t); },
                setTheme: function (dark) { return n.setThemeWithToken(!!dark, t); }
            };
        } else {
            console.warn('[piq-token] bridge-absent');
        }
        var e = window.PIQExport;
        if (e) {
            window.PIQExport = {
                save: function (name, b64, mime) { return e.saveWithToken(name, b64, mime, t); }
            };
        }
    })();
""".trimIndent()

/**
 * R2-a：单 Activity + WebView + 真实前端资产根 + `piq-android` 门控 + JS 桥。
 *
 * 不申请 `INTERNET`：资产全部来自 APK 内 `assets/`，缺文件返回 404；
 * `api/` 前缀下的路径一律交给 [ApiGatewayHandler]（⇒ [apiGateway] ⇒ [PiqRouter]），
 * **不交回网络栈**（`docs/契约/跨端契约.md` §5 订正块：放行会把"端上有实现"伪装成"取回失败"）。
 * 系统栏留白（edge-to-edge insets）由 [PiqInsets] 算并注入成 CSS 变量，前端只消费（`AGENTS.md` §3.3.2）。
 *
 * R11 起它还是提醒层的 [PiqReminderHooks] 实现方与 [ReminderSource] 提供方：
 * 权限框只能由 Activity 发起，而预计开始日只能查库 —— 两件事都不属于设置桥本身。
 */
class MainActivity : Activity(), PiqReminderHooks {

    private lateinit var prefs: SharedPreferences
    private lateinit var webView: WebView
    private lateinit var themeSource: PiqRootSource
    private lateinit var assetLoader: WebViewAssetLoader
    private lateinit var insets: PiqInsets

    private lateinit var settings: PiqSettings
    private lateinit var sessions: SessionHolder
    private lateinit var hostSettings: HostSettingsBridge
    private lateinit var fileChooser: PiqFileChooser

    /**
     * 业务侧服务对象：DB / Chaquopy 都不在主线程创建 —— 首次桥调用（JS 桥线程）
     * 才惰性建立，`Python.start` 与 DDL 都发生在那条线程上。
     *
     * 改字号/语言/密度会让本 Activity 重建，于是这里会再多一个 `PiqServices`；但**库是进程级
     * 单实例**（`PiqDb.shared`）⇒ 重建不产生第二套库句柄（F-R10.2-01）。`onDestroy` 因此
     * 也**不关**那个句柄：它的生命周期跟进程一致。
     */
    private val services by lazy { PiqServices(applicationContext, sessions, BuildConfig.VERSION_NAME) }

    /**
     * 本机 REST 的唯一入口（契约 §5：`request(method, path, body) -> {status, json}`）。
     * JS 桥（`PIQNative.request`）与资源请求拦截（[ApiGatewayHandler]）共用它
     * ⇒ 两条通道对同一个 `api/` 前缀路径必然给同一个答案。
     *
     * 写成 lambda 而不是直接持有路由，是为了**保住惰性**：首次取 `services` 会建表并
     * `Python.start`，那不该发生在 `onCreate`（主线程），只在第一条请求真的到来时
     * ——JS 桥线程或 WebView 工作线程——才发生。
     */
    private val apiGateway = PiqApiGateway { method, path, body ->
        settleStartupSession(path)
        val envelope = services.router.handle(method, path, body)
        afterApiServed(method, path, envelope.status)
        envelope
    }

    /**
     * 提醒层的"一次 API 服务完了"这条腿（R11 §4 七条重排腿里的四条都在这个漏斗上）：
     * 登录 / 首屏拿到预测 / 周期增删改 / 导入 ⇒ 重排；登出 / 删号 ⇒ 全取消；改密 ⇒ 不取消；
     * 401 按 [PiqReminderRoutes.action] 的口径处理（会话失效才全取消）。
     *
     * 判定交给纯函数 [PiqReminderRoutes.action]（JVM 可测），这里只负责分发；
     * 排程本身排在提醒层的执行器线程上 —— 本漏斗可能跑在资源拦截线程或 JS 桥线程，
     * 但**绝不**该在主线程查库（[PiqServices] 的惰性纪律同样适用于这里）。
     */
    private fun afterApiServed(method: String, path: String, status: Int) {
        when (PiqReminderRoutes.action(method, path, status)) {
            ReminderAction.RESCHEDULE ->
                PiqReminderScheduler.rescheduleAsync(applicationContext, reminderSource, "api-$method-$path")

            ReminderAction.CANCEL_ALL ->
                PiqReminderScheduler.cancelAllAsync(applicationContext, "api-$method-$path")

            ReminderAction.NONE -> Unit
        }
        if (status == 200 && pendingCalendarNotifyId != 0 && method.equals("POST", ignoreCase = true) &&
            (path == "/auth/login" || path == "/auth/register")
        ) {
            webView.post { applyPendingCalendarRoute() }
        }
    }

    /**
     * 排程要问宿主的两个事实：现在有没有登录、预计开始日是哪天。
     *
     * 查库走**进程级单实例**句柄（[PiqDb.shared]）与 [PiqCrud.getLatestPrediction]，
     * 不起 Chaquopy、不碰 WebView：闹钟只需要一个日期，算法重算是路由那条路的事。
     * 登录态用 [SessionHolder.current] + `getSessionUser` 的同一条 SQL —— 与
     * [PiqRouter.requireUser] 同一个口径，避免出现"路由说没登录、排程说有"。
     */
    private val reminderSource = object : ReminderSource {

        override fun isLoggedIn(): Boolean = sessions.current() != null

        override fun predictedStart(): LocalDate? {
            val token = sessions.current() ?: return null
            val crud = PiqCrud(PiqDb.shared(applicationContext))
            val userId = crud.getSessionUser(token)?.optLong("id", 0L) ?: 0L
            if (userId == 0L) return null
            val latest = crud.getLatestPrediction(userId) ?: return null
            return PyMath.parseIsoDate(latest.optString("predicted_date"))
        }
    }

    private var variant: String = VariantPolicy.DEFAULT
    private lateinit var decision: ThemeDecision

    /**
     * 当前文档 URL 的**主线程快照**（R13.1 F3 设备实测修正）。
     *
     * `PiqBridge` 跑在 WebView 的 JavaBridge 线程，Android 规定 `WebView.getUrl()`
     * 只能在创建它的主线程调用；桥里直接读会抛
     * "A WebView method was called on thread 'JavaBridge'"，所有桥调用一起失效。
     * 因此由主线程在导航回调里维护本字段，桥只读它（`@Volatile` 保证可见性）。
     */
    @Volatile
    private var currentPageUrl: String? = null

    /** 最后一个可信文档；用于把绕过 `shouldOverrideUrlLoading` 的导航拉回来（R13.1 F3 设备实测）。 */
    @Volatile
    private var lastTrustedPage: String? = null

    /** 通知点击待处理的路由目标；0 = 没有待处理。Intent 只带 id，不带任何日期。 */
    private var pendingCalendarNotifyId: Int = 0

    /** 是否已加载过应用页面：决定 `onNewIntent` 走重载还是原地注入路由。 */
    @Volatile
    private var appPageLoaded: Boolean = false

    /** 返回键正在等 Web 回答；连按时不重复投递，避免两次关闭/退出。 */
    @Volatile
    private var backPending: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingCalendarNotifyId = intent.getIntExtra(PiqReminderScheduler.EXTRA_NOTIFY_ID, 0)
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        settings = PiqSettings(this)
        sessions = SessionHolder(settings, SessionSlot.process)
        hostSettings = HostSettingsBridge(settings, sessions, this, this)
        val adopted = sessions.adoptPersisted()
        Log.i(TAG, "startup-session|stage=adopt|autoLogin=${settings.isAutoLoginEnabled()}|candidate=$adopted|ttl=${if (sessions.adoptionPending()) "pending" else "n/a"}")
        variant = resolveAndPersist(intent)
        bindThemeRoot(resolveTheme(variant))

        // CDP 取证需要：debug 构建开 devtools socket（release 构建不进入这个分支）
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        Log.i(TAG, "webview-debug|enabled=${BuildConfig.DEBUG}")

        webView = WebView(this)
        setContentView(webView)
        applySystemBarIcons(false)
        insets = PiqInsets(webView)
        insets.enable(window)
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                setAlgorithmicDarkeningAllowed(false)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                setForceDark(WebSettings.FORCE_DARK_OFF)
            }
        }
        fileChooser = PiqFileChooser(this)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(cm: ConsoleMessage): Boolean {
                Log.i(TAG, "console|level=${cm.messageLevel()}|src=${cm.sourceId()}:${cm.lineNumber()}|msg=${cm.message()}")
                return true
            }

            /**
             * `<input type=file>` 的选择请求交给 [PiqFileChooser]（R6 缺这条 ⇒ 点了没反应）。
             * 返回值即"宿主是否承接了这次请求"，不承接时 WebView 自行结束等待。
             */
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams,
            ): Boolean = fileChooser.open(params, callback)
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url)

            /**
             * 只允许应用自己的 asset origin（R13.1 F3）。`startsWith(ORIGIN)` 会放进
             * `appassets.androidplatform.net.evil`，所以判据由 [BridgeOriginGuard] 统一给出；
             * 返回 true = 取消导航，页面停在原文档。
             */
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                val allowed = BridgeOriginGuard.isTrusted(url)
                if (allowed) {
                    currentPageUrl = url
                    lastTrustedPage = url
                }
                if (!allowed) Log.w(TAG, "nav-blocked|${safeUrlForLog(url)}|origin=$ORIGIN")
                return !allowed
            }

            /**
             * CDP / 程序化导航可能绕过 `shouldOverrideUrlLoading`（R13.1 F3 设备实测：
             * `Page.navigate` 到 `data:` 时直接进了 onPageStarted）。这里把当前 URL 先记成
             * **实际文档**（桥随即拒绝），再停止加载并拉回最后一个可信页面。
             * 桥的最终判定另由文档启动令牌收紧（`data:` 文档拿不到令牌）。
             */
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                currentPageUrl = url
                if (!BridgeOriginGuard.isTrusted(url)) {
                    Log.w(TAG, "nav-blocked|stage=started|${safeUrlForLog(url)}|origin=$ORIGIN")
                    view.stopLoading()
                    lastTrustedPage?.let(view::loadUrl)
                    return
                }
                lastTrustedPage = url
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                if (BridgeOriginGuard.isTrusted(url)) {
                    currentPageUrl = url
                    lastTrustedPage = url
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                Log.w(TAG, "net-error|url=${request.url}|code=${error.errorCode}|desc=${error.description}")
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (BridgeOriginGuard.isTrusted(url)) {
                    currentPageUrl = url
                    lastTrustedPage = url
                    appPageLoaded = true
                }
                Log.i(TAG, "page-finished|url=$url|title=${view.title}")
                insets.repush()
                applyPendingCalendarRoute()
            }
        }
        webView.addJavascriptInterface(PiqBridge(), JS_BRIDGE)
        webView.addJavascriptInterface(
            PiqExport.Bridge(this, { currentPageUrl }, { BridgeToken.value }),
            PiqExport.BRIDGE_NAME,
        )
        installAndroidGate()

        logWebViewPackage()
        loadRoot()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingCalendarNotifyId = intent.getIntExtra(PiqReminderScheduler.EXTRA_NOTIFY_ID, 0)
        val previous = variant
        variant = resolveAndPersist(intent)
        Log.i(TAG, "new-intent|previous=$previous|now=$variant")
        bindThemeRoot(resolveTheme(variant))
        if (variant != previous || !appPageLoaded) {
            loadRoot()
        } else {
            applyPendingCalendarRoute()
        }
    }

    /**
     * 文件选择结果回填（[PiqFileChooser] 的请求码）。取消也要回填，否则 WebView 认为请求仍挂起，
     * 之后点「选择文件」不会再出选择器。
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PiqFileChooser.REQUEST_CODE) fileChooser.finish(resultCode, data)
        else if (requestCode == PiqExport.SAVE_AS_REQUEST_CODE) PiqExport.finishSaveAs(this, resultCode, data)
    }

    // ------------------------------------------------------------- R11 提醒层（PiqReminderHooks）

    /** 挂在权限框上等结果的回调：同一时刻最多一个（用户没点掉就不会再问第二次）。 */
    private var pendingPermissionCallback: ((Boolean) -> Unit)? = null

    override fun notificationPermissionGranted(): Boolean = PiqReminderScheduler.permissionGranted(this)

    /**
     * 用户亲手打开总开关 ⇒ 弹一次权限框。
     *
     * 两件事决定了这里的写法：设置写入跑在 JS 桥线程，而 `requestPermissions` 只能在主线程 ⇒
     * 统一 `runOnUiThread`；提示词 §2④ 要"不许循环弹框" ⇒ 已经有回调挂在框上就直接跳过。
     * API 32 及以下没有这个运行时权限，回调同步给 `true`（开关直接可用）。
     */
    override fun requestNotificationPermission(onResult: (granted: Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Log.i(TAG, "notify-permission|path=pre-33|granted=true")
            onResult(true)
            return
        }
        runOnUiThread {
            if (pendingPermissionCallback != null) {
                Log.i(TAG, "notify-permission|skipped=request-already-in-flight")
                return@runOnUiThread
            }
            pendingPermissionCallback = onResult
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                PiqReminderScheduler.PERMISSION_REQUEST_CODE,
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PiqReminderScheduler.PERMISSION_REQUEST_CODE) return
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        val callback = pendingPermissionCallback
        pendingPermissionCallback = null
        Log.i(TAG, "notify-permission|granted=$granted|callback=${if (callback == null) "none" else "delivered"}")
        callback?.invoke(granted)
    }

    override fun rescheduleAsync(reason: String) =
        PiqReminderScheduler.rescheduleAsync(applicationContext, reminderSource, reason)

    override fun cancelAllAsync(reason: String) =
        PiqReminderScheduler.cancelAllAsync(applicationContext, reason)

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    /**
     * 返回键 = 先问 Web 层（弹层 / 子页），未消费才回退 WebView 历史（R14.3.1）。
     *
     * 历史页打开 sheet 时 `history.length=3` 但 `canGoBack()=false`；只走 `goBack()` 会直接
     * 退出应用。前端 `__piqHandleBack` 负责关最上层 modal 或子页回父级；宿主只消费它的
     * `true`，其余仍按既有 `canGoBack()` / `super.onBackPressed()` 处理。
     */
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (backPending) return
        if (isFinishing || isDestroyed) {
            super.onBackPressed()
            return
        }
        backPending = true
        try {
            webView.evaluateJavascript(
                "(function(){try{return String(window.__piqHandleBack && window.__piqHandleBack())}catch(e){return 'false'}})()",
            ) { result ->
                runOnUiThread { finishBack(result) }
            }
        } catch (e: Throwable) {
            backPending = false
            Log.w(TAG, "back-js|handled=false|reason=webview-unavailable|type=${e.javaClass.simpleName}")
            fallbackBack()
        }
    }

    private fun finishBack(result: String?) {
        backPending = false
        if (isFinishing || isDestroyed) {
            super.onBackPressed()
            return
        }
        // evaluateJavascript 返回的是 JSON 形态：字符串 "true" 会带引号，布尔 true 不带。
        if (result?.trim()?.trim('"') == "true") {
            Log.i(TAG, "back-js|handled=true")
            return
        }
        fallbackBack()
    }

    @Suppress("DEPRECATION")
    private fun fallbackBack() {
        if (webView.canGoBack()) {
            val before = webView.url
            webView.goBack()
            // 只记 from=：goBack() 是异步导航，立刻读 webView.url 会把"还没动"读成"动到了"，
            // 那条读数会误导设备侧判据（R8 的 V-4）。回退后的真实地址由 onPageFinished 那条日志给。
            Log.i(TAG, "back-navigate|from=$before|canGoBackNow=${webView.canGoBack()}")
        } else {
            Log.i(TAG, "back-exit|reason=no-webview-history")
            super.onBackPressed()
        }
    }

    /**
     * 资产根选择入口（修复提示词1 §B1 授权的形态）：`MainActivity` 的**启动 extra** `ui_variant`。
     * 不是导出组件、不是 broadcast；白名单之外的取值一律忽略并回落 `default`，且不写盘。
     * R3 的设置页（契约 §7.2 的 `app_settings.ui_variant` + 密码门）将替换它。
     */
    private fun resolveAndPersist(source: Intent?): String {
        val extra = source?.getStringExtra(EXTRA_UI_VARIANT)
        val stored = prefs.getString(KEY_UI_VARIANT, null)
        val resolved = VariantPolicy.resolve(extra, stored)
        val writable = VariantPolicy.persistable(extra)
        if (writable != null && writable != stored) {
            prefs.edit().putString(KEY_UI_VARIANT, writable).apply()
        }
        Log.i(TAG, "resolve-variant|extra=$extra|stored=$stored|resolved=$resolved|written=${writable != null}")
        return resolved
    }

    /**
     * 主题根探测（契约 §7.1 硬护栏）：**在选根之前**把候选根验一遍，
     * 缺失 / 损坏 / `minApp` 不满足一律回退内置默认根，并把可读原因落日志（R2-a 的缺陷：
     * 请求时才解析路径 ⇒ 根不存在只在加载期 404 ⇒ 页面变成 `chrome-error://`）。
     */
    private fun resolveTheme(requested: String): ThemeDecision =
        ThemeResolver.resolve(assets, File(filesDir, DIR_THEMES), requested, BuildConfig.VERSION_NAME)

    /** 按判定结果换根：重建 handler 与 loader（`/shared/` 恒指默认根，不随主题切换）。 */
    private fun bindThemeRoot(decision: ThemeDecision) {
        this.decision = decision
        themeSource = when (val root = decision.root) {
            is ThemeRoot.Assets -> AssetRootHandler(assets, root.prefix)
            is ThemeRoot.Installed -> FileRootHandler(root.dir, ThemeResolver.label(root))
        }
        // 注册顺序即匹配顺序（第一个前缀命中就用它，**不是**最长前缀优先）：
        // 所以不能再单独注册 /assets/——它会把 /assets/x 解析成 www/x，
        // 而真实前端要的是 www/assets/x。静态绝对路径 /js /css /fonts /assets 一律交给根兜底。
        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler(PATH_THEME, themeSource)
            .addPathHandler(PATH_ROOT, ApiGatewayHandler(themeSource, apiGateway))
            .build()
        val reason = decision.fallbackReason
        if (reason == null) {
            Log.i(TAG, "theme-bound|requested=${decision.requested}|root=${themeSource.rootLabel()}|entry=${decision.entry}|evidence=${decision.evidence}")
        } else {
            Log.w(TAG, "theme-fallback|requested=${decision.requested}|effective=default|root=${themeSource.rootLabel()}|reason=$reason|evidence=${decision.evidence}")
        }
    }

    private fun loadRoot() {
        val deeplink = if (pendingCalendarNotifyId != 0) "#/calendar" else ""
        val url = ORIGIN + PATH_THEME + decision.entry + deeplink
        Log.i(TAG, "load-root|variant=$variant|root=${themeSource.rootLabel()}|url=$url")
        webView.loadUrl(url)
    }

    /**
     * 把通知点击带到日历页（R13.3）：只在页内 `App.user` 就绪时改 hash。
     *
     * 未登录/前端未就绪返回 0 ⇒ 200ms 后重试，10 次为止；超时**不清 pending**，
     * 登录成功（`afterApiServed` 的 200）再触发。日志只带 notifyId 与结果，不带日期。
     */
    private fun applyPendingCalendarRoute(attempt: Int = 1) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            webView.post { applyPendingCalendarRoute(attempt) }
            return
        }
        val notifyId = pendingCalendarNotifyId
        if (notifyId == 0) return
        webView.evaluateJavascript(
            "(function(){if(typeof App!=='undefined'&&App.user){location.hash='#/calendar';return '1';}return '0';})()",
        ) { raw ->
            if (pendingCalendarNotifyId != notifyId) return@evaluateJavascript
            if (raw?.trim()?.trim('"') == "1") {
                pendingCalendarNotifyId = 0
                Log.i(TAG, "deeplink|route=calendar|notifyId=$notifyId|applied=true")
            } else if (attempt >= MAX_DEEPLINK_ATTEMPTS) {
                Log.i(TAG, "deeplink|route=calendar|notifyId=$notifyId|applied=false")
            } else {
                webView.postDelayed({ applyPendingCalendarRoute(attempt + 1) }, DEEPLINK_RETRY_MS)
            }
        }
    }

    /**
     * 首条请求被服务之前结算启动期的候选凭证（L2E-03）。
     *
     * **为什么挂在这个位置**：[apiGateway] 是桥（[PiqBridge]）与资源拦截（`ApiGatewayHandler`）
     * 两条通道共同的漏斗，在它分发之前判一次，就保证过期凭证**没有**授权过任何一次请求；
     * 判据由 [PiqServices.isSessionLive] 给出，与 `PiqRouter.requireUser` 用的是同一条 SQL。
     *
     * **为什么不在 `onCreate` 里判**：有效性只有数据库知道，而建表与 `Python.start` 按
     * [PiqServices] 的纪律不得落在主线程；启动期主线程唯一能做的是"解出候选"。
     *
     * ⚠ 页内的令牌播种（[installAndroidGate]）发生在结算之前 —— 这是有意的：播种撤掉的话，
     * 前端在"没有令牌"的形态下根本不发请求，结算点就没有触发时机，用户会被静默停在登录页，
     * 与这条缺陷要求的"看得见一句解释"相反。
     */
    private fun settleStartupSession(path: String) {
        if (!sessions.adoptionPending()) return
        val autoLoginBefore = settings.isAutoLoginEnabled()
        val outcome = sessions.settleAdoption { token -> services.isSessionLive(token) }
        if (outcome != SessionSettlement.EXPIRED) {
            Log.i(TAG, "startup-session|stage=settle|adopted=${outcome == SessionSettlement.KEPT}|reason=${if (outcome == SessionSettlement.KEPT) "live" else "no-candidate"}|path=$path")
            return
        }
        showUserHint(this, SESSION_EXPIRED_HINT)
        Log.i(TAG, "startup-session|stage=settle|adopted=false|reason=expired|autoLogin=$autoLoginBefore>${settings.isAutoLoginEnabled()}|hint=shown|path=$path")
    }

    /**
     * 契约 §4 的 `piq-android` 注入点：不改前端源码，只影响行为。
     *
     * 唯一一处例外是版本串（[AndroidGate.versionRewrite]，R10.1-VER）：快照那一行渲染的是
     * **桌面前端**的版本号，装机用户看到的就是个对不上的版本，所以宿主把**渲染结果**改成
     * `versionName`。除这一行的值以外，文案与业务语义一律不动。
     */
    private fun installAndroidGate() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            Log.w(TAG, "gate-unsupported|feature=DOCUMENT_START_SCRIPT|minWebView=83|consequence=blocked")
            webView.visibility = View.GONE
            AlertDialog.Builder(this)
                .setTitle("需要更新系统 WebView")
                .setMessage("系统 WebView 版本过低，请更新 Android System WebView 后重试。")
                .setCancelable(false)
                .setPositiveButton("退出") { _, _ -> finish() }
                .show()
            return
        }
        val seed = sessions.current()
        WebViewCompat.addDocumentStartJavaScript(
            webView,
            AndroidGate.documentStartScript(seed, BuildConfig.VERSION_NAME) + "\n" + PiqExport.SCRIPT + "\n" +
                bridgeTokenScript(BridgeToken.value),
            setOf(ORIGIN),
        )
        Log.i(TAG, "gate-installed|class=${AndroidGate.CLASS_FLAG}|origin=$ORIGIN|exportBridge=${PiqExport.BRIDGE_NAME}|tokenSeed=${if (seed == null) "none" else "len" + seed.length}|bridgeToken=len${BridgeToken.value.length}")
    }

    private fun logWebViewPackage() {
        val pkg = WebView.getCurrentWebViewPackage()
        Log.i(TAG, "webview-package|name=${pkg?.packageName}|version=${pkg?.versionName}")
    }

    /**
     * JS 桥（契约 §5 的请求-响应形态：`request(method, path, body) -> {status, json}`）。
     *
     * R3 起 `request` 转 [PiqRouter]（本机实现）。**计数口径写全**：`method ==` 守卫行 31 条 = 字面 `method` + `rest` 28 条 + 正则匹配 `CYCLE_ID` 2 条（`:137`、`:138`）+ `/debug/` 前缀兜底 1 条（`:169`）；**端点条数**以 `docs/契约/跨端契约.md` 的清单为准，注释里不写死；未实现分支由路由给 501。
     * `getSetting` / `setSetting` 是**宿主机设置**通道（自动登录开关，契约 §7.2 命名域），
     * 只在 `piq-android` 门控内的 UI 里被调用，不是任何 REST 端点的替身。
     *
     * 跑在 WebView 的 JS 桥线程上（非 `main`）；日志只记长度与状态码，**不记 body 内容**
     * ——登录/注册响应里含会话令牌（`AGENTS.md` §5、契约 §7.2：凭证不得进入日志）。
     */
    inner class PiqBridge {

        /** 非应用 origin 的文档不得调用任何桥方法（R13.1 F3）；只读主线程快照，不碰 WebView。 */
        private fun trustedDocument(): Boolean = BridgeOriginGuard.isTrusted(currentPageUrl)

        /** 令牌只注入 ORIGIN 文档；`data:` 文档即使拖到拉回后执行也拿不到（R13.1.1 F3 竞态）。 */
        private fun tokenValid(token: String?): Boolean = token != null && token == BridgeToken.value

        private fun rejectUntrusted(operation: String, token: String?): String? {
            val trusted = trustedDocument()
            val tokenOk = tokenValid(token)
            if (trusted && tokenOk) return null
            val reason = if (!trusted) "untrusted-origin" else "bad-token"
            return rejectWithLog(operation, reason)
        }

        private fun rejectWithLog(operation: String, reason: String): String {
            Log.w(TAG, "bridge-origin-rejected|op=$operation|reason=$reason|${safeUrlForLog(currentPageUrl)}")
            return Envelope.failure(
                403,
                "当前页面不是应用内页面，已拒绝本机请求。",
                "FORBIDDEN_ORIGIN",
            ).toJsonString()
        }

        /** 旧的三参入口：没有令牌，直接 403（让 `data:` 文档的普通调用留下可观测拒绝）。 */
        @JavascriptInterface
        fun request(method: String?, path: String?, body: String?): String =
            rejectWithLog("request", "missing-token")

        @JavascriptInterface
        fun requestWithToken(method: String?, path: String?, body: String?, token: String?): String {
            rejectUntrusted("request", token)?.let { return it }
            val thread = Thread.currentThread().name
            if (method.isNullOrBlank() || path.isNullOrBlank()) {
                Log.w(TAG, "bridge-call-rejected|method=$method|path=$path|thread=$thread")
                return Envelope(
                    404,
                    JSONObject().put("detail", "缺少 method 或 path").put("code", "NOT_FOUND"),
                ).toJsonString()
            }
            Log.i(TAG, "bridge-call|method=$method|path=$path|bytes=${body?.utf8ByteLength() ?: 0}|chars=${body?.length ?: 0}|thread=$thread|onUiThread=${Looper.myLooper() == Looper.getMainLooper()}")
            val envelope = try {
                apiGateway.request(method, path, body)
            } catch (e: Throwable) {
                Log.w(TAG, "bridge-unhandled|path=$path|type=${e.javaClass.name}|message=${e.message}")
                genericFailure(500, "BRIDGE_ERROR")
            }
            // 序列化一次、两处复用（返回给页内的就是这一个串）；两腿都记 bytes/chars 才对得上账
            val responseJson = envelope.toJsonString()
            Log.i(TAG, "bridge-return|path=$path|status=${envelope.status}|bytes=${responseJson.utf8ByteLength()}|chars=${responseJson.length}|thread=$thread")
            return responseJson
        }

        /** 无令牌入口：直接空回并留拒绝日志。 */
        @JavascriptInterface
        fun getSetting(key: String?): String {
            rejectWithLog("getSetting", "missing-token")
            return ""
        }

        @JavascriptInterface
        fun getSettingWithToken(key: String?, token: String?): String {
            if (rejectUntrusted("getSetting", token) != null) return ""
            return hostSettings.getSetting(key ?: "")
        }

        @JavascriptInterface
        fun setSetting(key: String?, value: String?): String {
            rejectWithLog("setSetting", "missing-token")
            return ""
        }

        @JavascriptInterface
        fun setSettingWithToken(key: String?, value: String?, token: String?): String {
            if (rejectUntrusted("setSetting", token) != null) return ""
            return hostSettings.setSetting(key ?: "", value ?: "")
        }

        @JavascriptInterface
        fun setThemeWithToken(dark: Boolean, token: String?): String {
            if (rejectUntrusted("setTheme", token) != null) return ""
            runOnUiThread { applySystemBarIcons(dark) }
            Log.i(TAG, "theme-bars|dark=$dark")
            return "ok"
        }
    }

    /** 系统栏图标跟随页面主题：亮底用深色图标，暗底用浅色图标。 */
    private fun applySystemBarIcons(dark: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = window.insetsController ?: return
            val lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            controller.setSystemBarsAppearance(if (dark) 0 else lightBars, lightBars)
        } else {
            @Suppress("DEPRECATION")
            val lightFlags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            @Suppress("DEPRECATION")
            val flags = if (dark) {
                window.decorView.systemUiVisibility and lightFlags.inv()
            } else {
                window.decorView.systemUiVisibility or lightFlags
            }
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = flags
        }
    }

    companion object {
        private const val TAG = "PIQ"
        private const val PREFS = "piq_spike"
        private const val KEY_UI_VARIANT = "ui_variant"
        private const val EXTRA_UI_VARIANT = "ui_variant"
        private const val JS_BRIDGE = "PIQNative"

        /** 启动期凭证过期时给用户的那一句（L2E-03：不得静默弹回登录页）。 */
        private const val SESSION_EXPIRED_HINT = "上次的登录已经过期，请重新输入口令。"

        private const val ORIGIN = BridgeOriginGuard.ORIGIN
        private const val PATH_THEME = "/theme/"
        private const val PATH_ROOT = "/"

        /** 已安装主题包的落点（契约 §7.1：安卓 `files/themes/<id>/`）。 */
        private const val DIR_THEMES = "themes"

        /** 通知深链：单次触发最多 10 次（约 2s），超时不清 pending。 */
        private const val MAX_DEEPLINK_ATTEMPTS = 10
        private const val DEEPLINK_RETRY_MS = 200L
    }
}
