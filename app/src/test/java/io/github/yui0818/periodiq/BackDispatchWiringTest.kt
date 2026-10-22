package io.github.yui0818.periodiq

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 宿主返回键先问 Web 层（R14.3.1）。
 *
 * `MainActivity` 与 WebView 需要 Android 运行时，JVM 造不出来 ⇒ 沿用 [ProductionSource]
 * 钉源码顺序：`evaluateJavascript(__piqHandleBack)` 必须在 `canGoBack()` 之前，
 * 并有 `backPending` 防重入；前端钩子必须先问 `ui.handleBack()`，再走子页 `goBack()`。
 */
class BackDispatchWiringTest {

    @Test
    fun `onBackPressed asks web before webview history`() {
        val source = ProductionSource.commentsRemoved("MainActivity.kt")
        val body = ProductionSource.functionBody("MainActivity.kt", "override fun onBackPressed()")
        val hookAt = body.indexOf("__piqHandleBack")
        val evaluateAt = body.indexOf("evaluateJavascript")
        val evaluateInSourceAt = source.indexOf("evaluateJavascript")
        val canGoBackAt = source.indexOf("canGoBack()")
        assertTrue("onBackPressed 没有调用前端 __piqHandleBack", hookAt >= 0)
        assertTrue("onBackPressed 没有调用 evaluateJavascript", evaluateAt >= 0)
        assertTrue("canGoBack() 必须在 evaluateJavascript 之后兜底", canGoBackAt >= 0 && evaluateInSourceAt < canGoBackAt)
        assertTrue("evaluateJavascript 的脚本必须包含 __piqHandleBack", body.substring(evaluateAt).contains("__piqHandleBack"))
    }

    @Test
    fun `onBackPressed guards reentry and keeps the old fallback`() {
        val source = ProductionSource.commentsRemoved("MainActivity.kt")
        val body = ProductionSource.functionBody("MainActivity.kt", "override fun onBackPressed()")
        val pendingAt = body.indexOf("backPending")
        val evaluateAt = body.indexOf("evaluateJavascript")
        assertTrue("onBackPressed 缺少 backPending 防重入", pendingAt >= 0)
        assertTrue("backPending 判断必须在 evaluateJavascript 之前", pendingAt < evaluateAt)
        assertTrue("未消费时必须保留 webView.goBack() 回退", source.contains("webView.goBack()"))
        assertTrue("无历史时必须保留 super.onBackPressed() 退出路径", source.contains("super.onBackPressed()"))
        assertTrue("无历史时必须保留既有 back-exit 日志", source.contains("back-exit|reason=no-webview-history"))
    }

    @Test
    fun `frontend hook closes modal before subpage fallback`() {
        val js = frontendAppJs()
        val hookAt = js.indexOf("window.__piqHandleBack")
        assertTrue("app.js 缺少 window.__piqHandleBack", hookAt >= 0)
        val uiBackAt = js.indexOf("ui.handleBack()", hookAt)
        val subpageBackAt = js.indexOf("goBack()", hookAt)
        assertTrue("钩子必须先调用 ui.handleBack()", uiBackAt > hookAt)
        assertTrue("钩子必须在询问弹层后再走子页 goBack()", subpageBackAt > uiBackAt)
        val hookEnd = js.indexOf("};", hookAt)
        assertTrue("找不到 __piqHandleBack 的结束位置", hookEnd > hookAt)
        val hookBody = js.substring(hookAt, hookEnd + 2)
        assertTrue("子页规则必须排除 login", hookBody.contains("route.name !== 'login'"))
        assertTrue("子页规则必须排除主 tab", hookBody.contains("!route.main"))
        assertTrue("未消费时必须返回 false", hookBody.contains("return false"))
    }

    private fun frontendAppJs(): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            val candidate = File(dir, "frontend/js/app.js")
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        throw IllegalStateException(
            "找不到 frontend/js/app.js，源守卫已失效（cwd=${System.getProperty("user.dir")}）",
        )
    }
}
