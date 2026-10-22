package io.github.yui0818.periodiq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * R1 占位单测：**不是**恒真断言 —— 每条都在钉住 Spike A 的实际语义
 * （入口白名单取值、非法输入回落、资产根映射、虚拟路径拼接、MIME 判定）。
 */
class VariantPolicyTest {

    @Test
    fun `no extra falls back to stored value, default when stored is unusable`() {
        assertEquals("custom", VariantPolicy.resolve(null, "custom"))
        assertEquals("default", VariantPolicy.resolve(null, null))
        assertEquals("default", VariantPolicy.resolve(null, "leftover-garbage"))
    }

    @Test
    fun `whitelisted extra overrides stored value in both directions`() {
        assertEquals("custom", VariantPolicy.resolve("custom", "default"))
        assertEquals("default", VariantPolicy.resolve("default", "custom"))
    }

    @Test
    fun `extra is normalized by trimming and lowercasing`() {
        assertEquals("custom", VariantPolicy.resolve("  CUSTOM ", "default"))
        assertEquals("default", VariantPolicy.resolve("Default", "custom"))
    }

    @Test
    fun `extra outside the whitelist is ignored and never persisted`() {
        assertEquals("default", VariantPolicy.resolve("bogus", "custom"))
        assertNull(VariantPolicy.persistable("bogus"))
        assertNull(VariantPolicy.persistable(null))
        assertEquals("custom", VariantPolicy.persistable(" Custom "))
    }

    @Test
    fun `each variant maps to its own asset root and unknown values keep the default root`() {
        assertEquals("www/", VariantPolicy.rootOf(VariantPolicy.DEFAULT))
        assertEquals("www-custom/", VariantPolicy.rootOf(VariantPolicy.CUSTOM))
        assertEquals("www/", VariantPolicy.rootOf("anything-else"))
    }

    @Test
    fun `virtual path joins onto the active root without doubling slashes`() {
        assertEquals("www/index.html", AssetRootHandler.join("www/", "index.html"))
        assertEquals("www/js/spike.js", AssetRootHandler.join("www", "/js/spike.js"))
        assertEquals("www/index.html", AssetRootHandler.join("", "/www/index.html"))
        assertEquals("x/y", AssetRootHandler.join("/x/", "/y"))
    }

    @Test
    fun `mime and encoding are derived from the asset extension`() {
        assertEquals("text/html", AssetRootHandler.mimeOf("www/index.html"))
        assertEquals("text/javascript", AssetRootHandler.mimeOf("www/js/spike.js"))
        assertEquals("application/octet-stream", AssetRootHandler.mimeOf("www/no_extension"))
        assertEquals("utf-8", AssetRootHandler.encodingOf("text/html"))
        assertEquals("binary", AssetRootHandler.encodingOf("image/png"))
    }
}
