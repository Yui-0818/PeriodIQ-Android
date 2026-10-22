package io.github.yui0818.periodiq

import io.github.yui0818.periodiq.api.contract.ApiError
import io.github.yui0818.periodiq.api.contract.Envelope
import io.github.yui0818.periodiq.api.contract.genericFailure

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R13.2 B1：信封与错误契约的特征测试（搬家前先绿，搬家后仍绿）。 */
class ApiEnvelopeContractTest {

    @Test
    fun `failure keeps detail and code and appends only explicit extras`() {
        val plain = Envelope.failure(422, "d", "E")
        assertEquals(422, plain.status)
        assertEquals(setOf("detail", "code"), keys(plain.json))
        assertEquals("d", (plain.json as JSONObject).getString("detail"))
        assertEquals("E", (plain.json as JSONObject).getString("code"))

        val withExtra = Envelope.failure(400, "d2", "E2", JSONObject().put("anomalies", 1))
        assertEquals(setOf("detail", "code", "anomalies"), keys(withExtra.json))
        assertEquals(1, (withExtra.json as JSONObject).getInt("anomalies"))
    }

    @Test
    fun `error delegates to failure with the same body`() {
        val err = ApiError(409, "冲突", "CONFLICT", JSONObject().put("hint", "x"))
        val viaError = Envelope.error(err)
        val viaFailure = Envelope.failure(409, "冲突", "CONFLICT", JSONObject().put("hint", "x"))
        assertEquals(409, viaError.status)
        assertEquals(viaFailure.json.toString(), viaError.json.toString())
        assertEquals(setOf("detail", "code", "hint"), keys(viaError.json))
    }

    @Test
    fun `generic failure keeps the user readable detail and code`() {
        val e = genericFailure(500, "INTERNAL_ERROR")
        assertEquals(500, e.status)
        assertEquals(setOf("detail", "code"), keys(e.json))
        val body = e.json as JSONObject
        assertEquals("INTERNAL_ERROR", body.getString("code"))
        assertEquals(
            "本机处理时出了点问题，这次没能完成。请再试一次；如果一直失败，可以把情况告诉开发者（错误码 INTERNAL_ERROR）。",
            body.getString("detail"),
        )
        assertFalse(body.getString("detail").contains("Exception"))
        assertFalse(body.getString("detail").contains("java."))
        assertFalse(body.getString("detail").contains("/api/"))
    }

    @Test
    fun `501 envelope has exactly three keys`() {
        val e = notImplementedEnvelope("原因")
        assertEquals(501, e.status)
        assertEquals(setOf("detail", "code", "implemented"), keys(e.json))
        val body = e.json as JSONObject
        assertEquals("原因", body.getString("detail"))
        assertEquals("NOT_IMPLEMENTED", body.getString("code"))
        assertFalse(body.getBoolean("implemented"))
    }

    private fun keys(json: Any): Set<String> = (json as JSONObject).keys().asSequence().toSet()
}
