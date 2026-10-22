package io.github.yui0818.periodiq.api.projection

import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject
import io.github.yui0818.periodiq.PyMath
import io.github.yui0818.periodiq.boolAt
import io.github.yui0818.periodiq.textOrNull

/** 字段投影（对齐桌面 pydantic 响应模型：声明之外的列一律不外泄）。 */
object ApiProjection {

    /** `schemas.AuthUserResponse` + `auth._user_payload`（含后端算出的 age）。 */
    fun user(row: JSONObject): JSONObject {
        val birthday = row.textOrNull("birthday")
        return JSONObject()
            .put("id", row.optLong("id"))
            .put("username", row.optString("username"))
            .put("nickname", row.nullableString("nickname"))
            .put("avatar", row.nullableString("avatar"))
            .put("birthday", row.nullableString("birthday"))
            .put("weight_kg", row.nullableNumber("weight_kg"))
            .put("age", computeAge(birthday))
            .put("created_at", row.nullableString("created_at"))
    }

    /** `auth._compute_age`：由生日推算，非法/缺失返回 null。 */
    fun computeAge(birthday: String?): Any {
        val b = PyMath.parseIsoDate(birthday) ?: return JSONObject.NULL
        val today = LocalDate.now()
        val beforeBirthday = today.monthValue < b.monthValue ||
            (today.monthValue == b.monthValue && today.dayOfMonth < b.dayOfMonth)
        return (today.year - b.year - if (beforeBirthday) 1 else 0).toLong()
    }

    /** `schemas.CycleResponse`（is_anomalous 走 bool 语义）。 */
    fun cycle(row: JSONObject): JSONObject = JSONObject()
        .put("id", row.optLong("id"))
        .put("period_start", row.optString("period_start"))
        .put("period_end", row.nullableString("period_end"))
        .put("cycle_length", row.nullableNumber("cycle_length"))
        .put("period_days", row.nullableNumber("period_days"))
        .put("note", row.optString("note"))
        .put("is_anomalous", row.boolAt("is_anomalous"))
        .put("created_at", row.nullableString("created_at"))

    /** `schemas.PredictionHistoryItem`（去掉 user_id，其余逐键）。 */
    fun predictionHistoryItem(row: JSONObject): JSONObject = JSONObject()
        .put("id", row.optLong("id"))
        .put("predicted_date", row.optString("predicted_date"))
        .put("predicted_start_upper", row.optString("predicted_start_upper"))
        .put("predicted_start_lower", row.optString("predicted_start_lower"))
        .put("confidence", row.opt("confidence"))
        .put("model_version", row.optString("model_version"))
        .put("model_params_json", row.optString("model_params_json"))
        .put("actual_date", row.nullableString("actual_date"))
        .put("error_days", row.nullableNumber("error_days"))
        .put("created_at", row.nullableString("created_at"))

    fun cycles(rows: List<JSONObject>, project: (JSONObject) -> JSONObject): JSONArray =
        JSONArray().also { a -> rows.forEach { a.put(project(it)) } }
}

/** 缺键 / JSON null → `JSONObject.NULL`（桌面 pydantic 的 Optional 字段序列化成 null）。 */
fun JSONObject.nullableString(name: String): Any = if (isNull(name)) JSONObject.NULL else opt(name)

/** 数值列的可空投影：整数保持整数形态（与 Python `int` 序列化一致，不写成 `4.0`）。 */
fun JSONObject.nullableNumber(name: String): Any {
    if (isNull(name)) return JSONObject.NULL
    return when (val raw = opt(name)) {
        is Number -> raw
        is String -> raw.toDoubleOrNull()?.let { JSONObject.wrap(it) } ?: JSONObject.NULL
        else -> JSONObject.NULL
    }
}
