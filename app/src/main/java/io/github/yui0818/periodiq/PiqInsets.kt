package io.github.yui0818.periodiq

import android.os.Build
import android.util.Log
import android.view.Window
import android.view.WindowInsets
import android.webkit.WebView
import kotlin.math.ceil

/**
 * 系统栏留白（insets）的**唯一计算处**（`Role/执行端.md` §5.5 的单源纪律）：
 * 宿主算出四条边并注入成 CSS 变量，前端只在 `piq-android` 门控内消费变量。
 *
 * 为什么必须显式处理：`targetSdk ≥ 35` 起 Android 15 **强制 edge-to-edge**（`AGENTS.md` §3.3.2），
 * 窗口铺到状态栏/导航栏底下；R6 之前的实测后果是"状态栏压标题、导航栏压底部 tab 栏"。
 *
 * ⚠ 两处换算只在这里做一次，前端不再猜：
 *  1. `WindowInsets` 给的是**设备像素**，而 CSS 的 `1px` 是**逻辑像素**
 *     （真机 420dpi ⇒ `window.devicePixelRatio = 2.625`）⇒ 必须除以 density；
 *  2. 除法一律**向上取整**：少 1px 就是"仍然被遮"（判据要 `top ≥ inset.top`），
 *     多不到 1px 只是留白略大。
 */
class PiqInsets(private val webView: WebView) {

    /** 四条边的**CSS 像素**值（注入与判据都用这一套单位）。 */
    data class Bars(val top: Int, val right: Int, val bottom: Int, val left: Int) {

        /**
         * 逐条写 `documentElement` 的行内自定义属性；页面还没出 `<html>` 时整段跳过。
         * 末尾那条自定义事件是给页内键盘滚动用的通知（`AndroidGate.KEYBOARD_SCROLL`）：
         * edge-to-edge 下键盘弹出**不一定**触发 `visualViewport` 事件，页面需要一个
         * "变量刚变过"的时机去重算可视带。
         */
        fun assignScript(): String {
            val pairs = listOf(VAR_TOP to top, VAR_RIGHT to right, VAR_BOTTOM to bottom, VAR_LEFT to left)
            val body = pairs.joinToString("\n") { (name, px) ->
                "document.documentElement.style.setProperty('$name', '${px}px');"
            }
            return "if (document.documentElement) {\n" +
                "$body\n" +
                "window.dispatchEvent(new Event('$EVENT_NAME'));\n" +
                "}"
        }
    }

    private var applied: Bars? = null

    /**
     * 打开 edge-to-edge 并开始监听。
     *
     * API < 30 上既没有 `Window.setDecorFitsSystemWindows` / `WindowInsets.Type`
     * （SDK `api-versions.xml` 实测三者的 since 都是 30），也**不会**被强制 edge-to-edge
     * ⇒ 保持"窗口自己给系统栏让位"的旧布局，一个变量都不注入（前端按 `var(..., 0px)` 兜底，
     * 因此桌面无变量与低版本都是同一句行为：留白 0）。
     */
    fun enable(window: Window) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.i(TAG, "insets|skipped|reason=API<30 不强制 edge-to-edge，也无对应 API")
            return
        }
        window.setDecorFitsSystemWindows(false)
        webView.setOnApplyWindowInsetsListener { view, insets ->
            // `ime()` 必须并进来：edge-to-edge 下窗口不会随键盘 resize，
            // 少了这一项就是"键盘盖住正在输入的框"（R8 L3 的 P-05，实测读数见 R8 报告 §3）。
            val bars = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime(),
            )
            val scale = view.resources.displayMetrics.density
            update(
                Bars(
                    top = cssPx(bars.top, scale),
                    right = cssPx(bars.right, scale),
                    bottom = cssPx(bars.bottom, scale),
                    left = cssPx(bars.left, scale),
                ),
                "device=[${bars.top},${bars.right},${bars.bottom},${bars.left}]|density=$scale",
            )
            insets
        }
    }

    /** 换页/重载后把当前值再推一遍：insets 没变但文档是新的，行内属性不会自己跟过来。 */
    fun repush() {
        applied?.let { webView.evaluateJavascript(it.assignScript(), null) }
    }

    private fun update(bars: Bars, deviceReadings: String) {
        if (bars == applied) return
        applied = bars
        Log.i(TAG, "insets|css=[${bars.top},${bars.right},${bars.bottom},${bars.left}]|$deviceReadings")
        webView.evaluateJavascript(bars.assignScript(), null)
    }

    private fun cssPx(devicePx: Int, density: Float): Int =
        if (density <= 0f) devicePx else ceil(devicePx / density.toDouble()).toInt()

    companion object {
        private const val TAG = "PIQ"

        /** 注入侧写 CSS 变量用的名单。⚠ 不是全仓单源：本组是 `private`，而门控内嵌 JS 里另硬编码了同一个名字——`AndroidGate.kt:104` 的 `'--piq-inset-bottom'` 与 `:235` 的 `'--piq-inset-top'`；消费侧在 `css/style.css`。 */
        private const val VAR_TOP = "--piq-inset-top"
        private const val VAR_RIGHT = "--piq-inset-right"
        private const val VAR_BOTTOM = "--piq-inset-bottom"
        private const val VAR_LEFT = "--piq-inset-left"

        /** 每次把新值推进页面后派发的时机事件；页内键盘滚动（`AndroidGate.KEYBOARD_SCROLL`）是它唯一的消费方。 */
        const val EVENT_NAME = "piq-insets-updated"
    }
}
