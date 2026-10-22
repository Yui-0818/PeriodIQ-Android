package io.github.yui0818.periodiq

/**
 * 资产根选择策略（R1 spike）。纯函数、无 Android 依赖 ⇒ 可直接单测。
 *
 * 语义（顾问 修复提示词1 §B1 第 3 条）：入口只读**一个白名单取值**，
 * 白名单之外的输入一律忽略并回落 `default`。
 */
object VariantPolicy {

    const val DEFAULT = "default"
    const val CUSTOM = "custom"

    private val ALLOWED = setOf(DEFAULT, CUSTOM)

    private const val ROOT_DEFAULT = "www/"
    private const val ROOT_CUSTOM = "www-custom/"

    /** 归一化：去空白 + 小写；不在白名单内返回 null。 */
    fun canonical(raw: String?): String? {
        val v = raw?.trim()?.lowercase() ?: return null
        return if (ALLOWED.contains(v)) v else null
    }

    /**
     * 本次生效值：
     * - extra 未给 ⇒ 沿用已存值（已存值非法则 `default`）；
     * - extra 合法 ⇒ 用 extra；
     * - extra 给了但不在白名单 ⇒ 忽略并回落 `default`。
     */
    fun resolve(extra: String?, stored: String?): String {
        if (extra == null) return canonical(stored) ?: DEFAULT
        return canonical(extra) ?: DEFAULT
    }

    /** 只有白名单内的 extra 才允许写盘（非法输入不得污染已存值）。 */
    fun persistable(extra: String?): String? = canonical(extra)

    fun rootOf(variant: String): String = if (variant == CUSTOM) ROOT_CUSTOM else ROOT_DEFAULT
}
