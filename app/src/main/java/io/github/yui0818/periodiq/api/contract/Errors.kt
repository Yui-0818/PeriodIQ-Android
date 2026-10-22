package io.github.yui0818.periodiq.api.contract

import org.json.JSONObject

/**
 * 422 扁平错误（桌面 pydantic 校验失败经 `main.py` 全局 handler 展平后的同形句子）。
 * 单源：路由壳与导入形态校验都用这一句，避免两处各写一遍文案模板。
 */
fun validationError(field: String, message: String) =
    ApiError(422, "请求参数校验失败（$field: $message）", "VALIDATION_ERROR")

/**
 * 兜底失败信封（R8 的 P-07）。**三处同族调用共用的单源**：桥 catch、路由 catch、资源网关 catch。
 *
 * 纪律：状态码与 `code` 逐字保留（契约 §5 的扁平错误信封），但 `detail` 一律是用户读得懂的中文——
 * Java 类名对用户零信息量，还会把内部实现甩进页面 DOM；技术细节的去处是 `Log.w`（三处都原本就在打）。
 */
fun genericFailure(status: Int, code: String): Envelope = Envelope(
    status,
    JSONObject()
        .put("detail", "本机处理时出了点问题，这次没能完成。请再试一次；如果一直失败，" +
            "可以把情况告诉开发者（错误码 $code）。")
        .put("code", code),
)
