package io.github.yui0818.periodiq.api.contract

import org.json.JSONObject

/**
 * 一次请求的结果信封。
 *
 * `json` 就是桌面 HTTP 响应体本身 —— 对象端点是对象、数组端点（`GET /cycles`、
 * `GET /predictions/history`）是数组，两端形态逐字同构。
 */
data class Envelope(val status: Int, val json: Any) {

    fun toJsonString(): String = JSONObject()
        .put("status", status)
        .put("json", json)
        .toString()

    companion object {
        /**
         * 扁平错误体的**主要**构造处（R8 的 REG-06 把 `Envelope.error` 与三处内联收拢到这里）。
         * ⚠ 全仓仍有 2 处不经它：本文件 `genericFailure`（`:68-74`）与 `MainActivity.kt:277` 的 `NOT_FOUND` 体内联。
         *
         * 与桌面全局 handler 同形：`{detail, code}` + 业务附加字段（如 DATA_ANOMALY.anomalies）。
         */
        fun failure(status: Int, detail: String, code: String, extra: JSONObject? = null): Envelope {
            val body = JSONObject().put("detail", detail).put("code", code)
            extra?.keys()?.forEach { key -> body.put(key, extra.get(key)) }
            return Envelope(status, body)
        }

        /** 业务异常 → 信封（走 [failure] 单源）。 */
        fun error(err: ApiError): Envelope = failure(err.status, err.detail, err.code, err.extra)
    }
}
