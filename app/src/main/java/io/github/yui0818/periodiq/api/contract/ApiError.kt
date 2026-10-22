package io.github.yui0818.periodiq.api.contract

import org.json.JSONObject

/** 业务异常（等价于桌面的 `HTTPException(detail={detail, code})`）。 */
class ApiError(
    val status: Int,
    val detail: String,
    val code: String,
    val extra: JSONObject? = null,
) : RuntimeException("$code: $detail")
