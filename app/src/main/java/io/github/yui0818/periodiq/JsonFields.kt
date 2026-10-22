package io.github.yui0818.periodiq

import org.json.JSONObject

/** JSON 文本列读数：`null` / 缺键 / 空串统一视为"无值"（与 Python 的 `None` 对齐）。 */
fun JSONObject.textOrNull(name: String): String? {
    if (isNull(name)) return null
    return optString(name).takeIf { it.isNotEmpty() }
}

fun JSONObject.doubleOrNull(name: String): Double? {
    if (isNull(name)) return null
    val raw = opt(name)
    return when (raw) {
        is Number -> raw.toDouble()
        is String -> raw.toDoubleOrNull()
        else -> null
    }
}

fun JSONObject.longOrNull(name: String): Long? {
    if (isNull(name)) return null
    val raw = opt(name)
    return when (raw) {
        is Number -> raw.toLong()
        is String -> raw.toLongOrNull()
        else -> null
    }
}

fun JSONObject.boolAt(name: String): Boolean = when (opt(name)) {
    is Boolean -> getBoolean(name)
    is Number -> getLong(name) != 0L
    else -> false
}
