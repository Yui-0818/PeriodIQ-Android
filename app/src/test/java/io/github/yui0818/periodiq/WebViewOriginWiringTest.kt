package io.github.yui0818.periodiq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WebView 导航与两条 JS 桥的 origin 守卫接线（R13.1 F3 / R12 的 P2-03）。
 *
 * 行为面由 [BridgeOriginGuardTest] 钉住“什么算应用内”；这里钉的是三处**调用点**：
 * ① 导航拦截；② `PiqBridge.request/getSetting/setSetting`；③ `PIQExport.save`。
 * 红路径：删掉 `shouldOverrideUrlLoading` 的守卫 ⇒ 第 1 条红；把导出桥的 `{ webView.url }`
 * 换成常量 ⇒ 第 2 条红；把 `BuildConfig.DEBUG` 判断去掉 ⇒ 第 3 条红。
 */
class WebViewOriginWiringTest {

    private val activity: String = ProductionSource.code("MainActivity.kt")
    private val activityWithStrings: String = ProductionSource.commentsRemoved("MainActivity.kt")

    @Test
    fun `navigation is cancelled unless it stays on the asset origin`() {
        val nav = functionBody(activity, "override fun shouldOverrideUrlLoading(")
        assertTrue("导航拦截必须问同一个 origin 判据", nav.contains("BridgeOriginGuard.isTrusted(url)"))
        assertTrue("返回 true 必须是“不可信”的那一侧", Regex("""return\s+!allowed""").containsMatchIn(nav))
        assertTrue("被拦的导航要留可分辨日志", functionBody(activityWithStrings, "override fun shouldOverrideUrlLoading(").contains("nav-blocked"))
    }

    @Test
    fun `programmatic navigation that bypasses the override is pulled back`() {
        val started = functionBody(activity, "override fun onPageStarted(")
        assertTrue("先记住实际文档，桥才能拒绝非 origin 页面", Regex("""currentPageUrl\s*=\s*url""").containsMatchIn(started))
        assertTrue("非 origin 必须停止加载", started.contains("view.stopLoading()"))
        assertTrue("然后拉回最后一个可信页面", started.contains("lastTrustedPage?.let(view::loadUrl)"))
        assertTrue("拦在 onPageStarted 也要留日志",
            functionBody(activityWithStrings, "override fun onPageStarted(").contains("nav-blocked"))
    }

    @Test
    fun `both bridges are wired with a live origin probe`() {
        assertTrue(
            "PiqBridge 必须注册为 JS 接口",
            Regex("""addJavascriptInterface\(PiqBridge\(\),\s*JS_BRIDGE\)""").containsMatchIn(activity),
        )
        assertTrue(
            "导出桥必须每次读主线程维护的当前文档 URL 与进程级令牌，不能缓存也不能直接读 WebView",
            Regex("""PiqExport\.Bridge\(\s*this\s*,\s*\{\s*currentPageUrl\s*\}\s*,\s*\{\s*BridgeToken\.value\s*\}\s*\)""").containsMatchIn(activity),
        )
        assertTrue(
            "当前文档 URL 必须是 @Volatile 的主线程快照",
            Regex("""@Volatile\s+private\s+var\s+currentPageUrl\s*:\s*String\?""").containsMatchIn(activity),
        )

        val bridge = functionBody(activityWithStrings, "inner class PiqBridge")
        assertTrue("桥必须从当前文档 URL 判定 origin", bridge.contains("BridgeOriginGuard.isTrusted(currentPageUrl)"))
        assertTrue(
            "JavaBridge 线程不得直接碰 WebView（Android 会抛 wrong-thread）",
            !bridge.contains("webView.url"),
        )
        assertTrue("request 必须先过 origin 闸门", bridge.indexOf("rejectUntrusted(\"request\"") in 1 until bridge.indexOf("apiGateway.request"))
        assertTrue(
            "getSetting 必须先过 origin 闸门",
            bridge.indexOf("rejectUntrusted(\"getSetting\"") < bridge.indexOf("hostSettings.getSetting"),
        )
        val setAt = bridge.indexOf("hostSettings.setSetting")
        assertEquals(
            "setSetting 之前的 origin 闸门应恰好 4 处（声明 + request + getSetting + setSetting 自己）",
            4,
            Regex("""rejectUntrusted\(""").findAll(bridge.substring(0, setAt)).count(),
        )
    }

    @Test
    fun `export bridge rejects before any decoding or writing`() {
        val export = ProductionSource.commentsRemoved("PiqExport.kt")
        val save = functionBody(export, "fun saveWithToken(name: String?, base64: String?, mime: String?, token: String?): String")
        assertTrue("导出桥必须从宿主拿当前文档 URL", save.contains("BridgeOriginGuard.isTrusted(currentUrl())"))
        assertTrue("导出桥必须校验令牌", save.contains("expectedToken()"))
        assertTrue(
            "origin 与令牌判定必须在落盘之前",
            save.indexOf("BridgeOriginGuard.isTrusted") < save.indexOf("saveToDisk("),
        )
        assertTrue("拒绝回执必须可被页面识别", save.contains("FORBIDDEN_ORIGIN"))
    }

    @Test
    fun `web contents debugging is compiled in only for debug builds`() {
        val guarded = Regex(
            """if\s*\(BuildConfig\.DEBUG\)\s*\{\s*WebView\.setWebContentsDebuggingEnabled\(true\)\s*\}""",
        ).findAll(activity).count()
        assertEquals("调试开关必须且只能由 BuildConfig.DEBUG 控制", 1, guarded)
        assertEquals(
            "release 路径不得留下第二个无条件开启点",
            1,
            Regex("""setWebContentsDebuggingEnabled\(true\)""").findAll(activity).count(),
        )
    }

    @Test
    fun `the bridge token is process-wide and injected only into the app origin`() {
        assertTrue(
            "令牌必须是进程级单例，不能是 Activity 实例字段",
            Regex("""internal object BridgeToken\s*\{[\s\S]*?val value: String = newBridgeToken\(\)""").containsMatchIn(activity),
        )
        assertFalse("令牌不得再是 Activity 实例字段", Regex("""private val bridgeToken\b""").containsMatchIn(activity))
        val gate = functionBody(activityWithStrings, "private fun installAndroidGate(")
        assertTrue("令牌脚本必须随 document-start 注入", gate.contains("bridgeTokenScript(BridgeToken.value)"))
        assertTrue("document-start 必须只对 ORIGIN 生效", gate.contains("setOf(ORIGIN)"))

        val bridge = functionBody(activityWithStrings, "inner class PiqBridge")
        assertTrue("桥方法必须校验令牌", bridge.contains("tokenValid(token)"))
        assertTrue("令牌必须与进程级值比较", bridge.contains("token == BridgeToken.value"))
        assertTrue("令牌不匹配与 origin 不可信都要拒绝", bridge.contains("FORBIDDEN_ORIGIN"))

        val export = ProductionSource.commentsRemoved("PiqExport.kt")
        val save = functionBody(export, "fun saveWithToken(name: String?, base64: String?, mime: String?, token: String?): String")
        assertTrue("导出桥也必须校验令牌", save.contains("expectedToken()"))
    }

    @Test
    fun `bridge token is a 32-hex process singleton in source`() {
        assertTrue(
            "令牌必须是进程级单例对象",
            Regex("""internal object BridgeToken\s*\{[\s\S]*?val value: String = newBridgeToken\(\)""").containsMatchIn(activity),
        )
        assertEquals(32, BridgeToken.value.length)
        assertTrue(BridgeToken.value.matches(Regex("[0-9a-f]{32}")))
    }

    @Test
    fun `blocked navigation and bridge logs carry no raw url`() {
        val nav = functionBody(activityWithStrings, "override fun shouldOverrideUrlLoading(")
        val navLines = nav.lines().filter { it.contains("nav-blocked") }
        assertTrue("应有两处导航日志（override + onPageStarted），实测=${navLines.size}", navLines.size >= 2)
        assertTrue("两处导航日志都必须脱敏", navLines.all { it.contains("safeUrlForLog(url)") })
        assertFalse("导航日志不得写完整 URL", Regex("""url=${'$'}\{?url\}?""").containsMatchIn(nav))

        val bridge = functionBody(activityWithStrings, "inner class PiqBridge")
        val bridgeLines = bridge.lines().filter { it.contains("bridge-origin-rejected") }
        assertTrue("桥拒绝日志必须有脱敏参数", bridgeLines.isNotEmpty())
        assertTrue("桥拒绝日志都必须脱敏", bridgeLines.all { it.contains("safeUrlForLog(currentPageUrl)") })
        assertFalse("桥日志不得写完整 URL", Regex("""url=${'$'}\{?currentPageUrl\}?""").containsMatchIn(bridge))
    }

    @Test
    fun `unsupported document start script blocks instead of injecting half a bridge`() {
        val gate = functionBody(activityWithStrings, "private fun installAndroidGate(")
        val branch = Regex(
            """if\s*\(!WebViewFeature\.isFeatureSupported\(WebViewFeature\.DOCUMENT_START_SCRIPT\)\)\s*\{([\s\S]*?)\n        \}""",
        ).find(gate)?.groupValues?.get(1)
        assertTrue("找不到 DOCUMENT_START_SCRIPT 不支持分支", branch != null)
        val body = branch!!
        assertTrue("必须留可检索的 gate-unsupported 日志", body.contains("gate-unsupported|feature=DOCUMENT_START_SCRIPT"))
        assertTrue("日志必须写最低 WebView 83", body.contains("minWebView=83"))
        assertTrue("必须给用户可读阻断提示", body.contains("AlertDialog"))
        assertTrue("阻断提示必须提供退出", body.contains("finish()"))
        assertTrue("阻断分支必须提前 return", Regex("""\breturn\b""").containsMatchIn(body))
        assertFalse("不得继续注入半套桥", body.contains("addDocumentStartJavaScript"))
        assertFalse("不得只留旧的 gate-skipped 日志", body.contains("gate-skipped"))
    }

    @Test
    fun `export logs carry no file name in rejected postFailure or injected script`() {
        val export = ProductionSource.commentsRemoved("PiqExport.kt")
        val rejected = functionBody(export, "private fun rejected(")
        assertFalse("拒绝日志不得写文件名", Regex("""name=${'$'}\{?name\}?""").containsMatchIn(rejected))
        assertTrue("只允许记长度", rejected.contains("nameLen="))

        val post = functionBody(export, "private fun postFailure(")
        assertFalse("失败回执不得写文件名", Regex("""name=${'$'}\{?name\}?""").containsMatchIn(post))
        assertTrue("失败回执只允许记长度", post.contains("nameLen="))

        val script = functionBody(export, "val SCRIPT: String =")
        assertFalse("注入脚本 console 不得写文件名", script.contains("bridge|name=' + name"))
        assertTrue("注入脚本 console 只允许记长度", script.contains("bridge|nameLen=' + name.length"))

        val save = functionBody(export, "fun saveWithToken(name: String?, base64: String?, mime: String?, token: String?): String")
        assertFalse(
            "origin 拒绝日志不得写文件名",
            save.contains("name=\${sanitizeName(name)}"),
        )
        assertTrue(
            "导出拒绝回执必须带 FORBIDDEN_ORIGIN 与 status=403 参数",
            save.contains("""result(false, sanitizeName(name), 0L, "FORBIDDEN_ORIGIN", 403)"""),
        )
    }

    private fun functionBody(code: String, signature: String): String {
        val start = code.indexOf(signature)
        require(start >= 0) { "找不到 $signature，源守卫已失效" }
        val next = Regex("""(?m)^    (private |internal |override |fun |class |inner )""")
            .find(code, start + signature.length)?.range?.first
        return code.substring(start, next ?: code.length)
    }
}
