package io.github.yui0818.periodiq

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WebView 文档边界（R13.1 F3 / R12-P2-03）。
 *
 * 判红手段：把 [BridgeOriginGuard.isTrusted] 改成裸 `startsWith(origin)`，
 * `lookalike host must not be trusted` 立刻红；把 null 当成可信，`null document` 红。
 */
class BridgeOriginGuardTest {

    @Test
    fun `only the asset origin and its paths are trusted`() {
        assertTrue(BridgeOriginGuard.isTrusted(BridgeOriginGuard.ORIGIN))
        assertTrue(BridgeOriginGuard.isTrusted("${BridgeOriginGuard.ORIGIN}/theme/index.html"))
        assertTrue(BridgeOriginGuard.isTrusted("${BridgeOriginGuard.ORIGIN}/api/v1/health?x=1"))
    }

    @Test
    fun `lookalike host must not be trusted`() {
        assertFalse(BridgeOriginGuard.isTrusted("${BridgeOriginGuard.ORIGIN}.evil"))
        assertFalse(BridgeOriginGuard.isTrusted("https://appassets.androidplatform.net.evil/theme/"))
        assertFalse(BridgeOriginGuard.isTrusted("http://appassets.androidplatform.net/"))
    }

    @Test
    fun `external documents and null are rejected`() {
        assertFalse(BridgeOriginGuard.isTrusted(null))
        assertFalse(BridgeOriginGuard.isTrusted("data:text/html,<p>x</p>"))
        assertFalse(BridgeOriginGuard.isTrusted("file:///android_asset/www/index.html"))
        assertFalse(BridgeOriginGuard.isTrusted("about:blank"))
        assertFalse(BridgeOriginGuard.isTrusted("content://com.example/x"))
    }
}
