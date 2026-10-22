package io.github.yui0818.periodiq

import io.github.yui0818.periodiq.api.projection.ApiProjection
import io.github.yui0818.periodiq.api.projection.nullableNumber
import io.github.yui0818.periodiq.api.projection.nullableString

import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** R13.2 B1：响应投影的特征测试（键集合、null 形态、整数形态、年龄边界）。 */
class ApiProjectionCharacterizationTest {

    @Test
    fun `user projection has exactly the desktop response keys and null shapes`() {
        val today = LocalDate.now()
        val birthdayPassed = today.minusYears(30).minusDays(1).toString()
        val row = JSONObject()
            .put("id", 7)
            .put("username", "alice")
            .put("weight_kg", 60)
            .put("birthday", birthdayPassed)
            .put("created_at", "2026-01-01T00:00:00")

        val user = ApiProjection.user(row)
        assertEquals(
            setOf("id", "username", "nickname", "avatar", "birthday", "weight_kg", "age", "created_at"),
            keys(user),
        )
        assertEquals(7L, user.getLong("id"))
        assertEquals("alice", user.getString("username"))
        assertSame(JSONObject.NULL, user.get("nickname"))
        assertSame(JSONObject.NULL, user.get("avatar"))
        assertEquals(birthdayPassed, user.getString("birthday"))
        assertEquals(60, (user.get("weight_kg") as Number).toInt())
        assertEquals(30L, user.getLong("age"))
        assertEquals("2026-01-01T00:00:00", user.getString("created_at"))
        assertTrue("整数不得写成 60.0：$user", user.toString().contains("\"weight_kg\":60"))
        assertTrue(!user.toString().contains("\"weight_kg\":60.0"))
    }

    @Test
    fun `computeAge keeps the birthday boundary and rejects invalid dates`() {
        val today = LocalDate.now()
        val passed = today.minusYears(30).minusDays(1)
        val tomorrow = today.minusYears(30).plusDays(1)
        assertEquals(30L, ApiProjection.computeAge(passed.toString()))
        assertEquals(29L, ApiProjection.computeAge(tomorrow.toString()))
        assertSame(JSONObject.NULL, ApiProjection.computeAge(null))
        assertSame(JSONObject.NULL, ApiProjection.computeAge("not-a-date"))
        assertSame(JSONObject.NULL, ApiProjection.computeAge(""))
    }

    @Test
    fun `cycle projection has exactly the desktop response keys and null shapes`() {
        val row = JSONObject()
            .put("id", 9)
            .put("period_start", "2026-01-01")
            .put("note", "")
            .put("is_anomalous", 1)
            .put("created_at", "2026-01-01T00:00:00")

        val cycle = ApiProjection.cycle(row)
        assertEquals(
            setOf("id", "period_start", "period_end", "cycle_length", "period_days", "note", "is_anomalous", "created_at"),
            keys(cycle),
        )
        assertEquals(9L, cycle.getLong("id"))
        assertEquals("2026-01-01", cycle.getString("period_start"))
        assertSame(JSONObject.NULL, cycle.get("period_end"))
        assertSame(JSONObject.NULL, cycle.get("cycle_length"))
        assertSame(JSONObject.NULL, cycle.get("period_days"))
        assertEquals("", cycle.getString("note"))
        assertEquals(true, cycle.getBoolean("is_anomalous"))
    }

    @Test
    fun `prediction history projection has exactly the desktop response keys and keeps number shapes`() {
        val row = JSONObject()
            .put("id", 3)
            .put("predicted_date", "2026-02-01")
            .put("predicted_start_upper", "2026-02-03")
            .put("predicted_start_lower", "2026-01-30")
            .put("confidence", 1)
            .put("model_version", "bayesian_v1")
            .put("model_params_json", "{}")
            .put("created_at", "2026-01-01T00:00:00")

        val item = ApiProjection.predictionHistoryItem(row)
        assertEquals(
            setOf(
                "id", "predicted_date", "predicted_start_upper", "predicted_start_lower",
                "confidence", "model_version", "model_params_json", "actual_date", "error_days", "created_at",
            ),
            keys(item),
        )
        assertEquals(3L, item.getLong("id"))
        assertEquals(1, (item.get("confidence") as Number).toInt())
        assertTrue("整数 confidence 不得写成 1.0：$item", !item.toString().contains("\"confidence\":1.0"))
        assertSame(JSONObject.NULL, item.get("actual_date"))
        assertSame(JSONObject.NULL, item.get("error_days"))
    }

    @Test
    fun `nullable helpers keep JSON null and number semantics`() {
        val row = JSONObject()
            .put("text", "x")
            .put("int", 4)
            .put("double", 4.5)
            .put("numericText", "4")

        assertEquals("x", row.nullableString("text"))
        assertSame(JSONObject.NULL, row.nullableString("missing"))
        assertEquals(4, (row.nullableNumber("int") as Number).toInt())
        assertEquals(4.5, (row.nullableNumber("double") as Number).toDouble(), 0.0)
        assertEquals(4.0, (row.nullableNumber("numericText") as Number).toDouble(), 0.0)
        assertSame(JSONObject.NULL, row.nullableNumber("missing"))
    }

    @Test
    fun `cycles helper preserves input order`() {
        val rows = listOf(
            JSONObject().put("id", 2).put("period_start", "2026-02-01").put("note", ""),
            JSONObject().put("id", 1).put("period_start", "2026-01-01").put("note", ""),
        )
        val array: JSONArray = ApiProjection.cycles(rows, ApiProjection::cycle)
        assertEquals(2, array.length())
        assertEquals(2L, array.getJSONObject(0).getLong("id"))
        assertEquals(1L, array.getJSONObject(1).getLong("id"))
    }

    private fun keys(json: JSONObject): Set<String> = json.keys().asSequence().toSet()
}
