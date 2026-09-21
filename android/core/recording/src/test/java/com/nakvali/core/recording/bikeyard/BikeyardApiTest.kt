package com.nakvali.core.recording.bikeyard

import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class BikeyardApiTest {
    private val tokenJson = """{"access_token":"test-access","refresh_token":"test-refresh","expires_in":21600,"expires_at":9999999,"token_type":"Bearer","scope":"profile:read rides:write","rider_id":"rider"}"""
    private fun api(code: Int, body: String, inspect: (Request) -> Unit) = BikeyardApi(
        OkHttpClient.Builder().addInterceptor { chain ->
            inspect(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code)
                .message("Test").body(body.toResponseBody()).build()
        }.build(),
    )

    @Test fun `public code exchange sends PKCE to selected environment without a secret`() = runBlocking {
        val api = api(200, tokenJson) { request ->
            assertEquals("https://sandbox.yard.bike/oauth/token", request.url.toString())
            val body = request.body as FormBody
            val fields = (0 until body.size).associate { body.name(it) to body.value(it) }
            assertEquals(BikeyardEnvironment.SANDBOX.clientId, fields["client_id"])
            assertEquals(BikeyardPkce.REDIRECT_URI, fields["redirect_uri"])
            assertEquals("verifier", fields["code_verifier"])
            assertEquals("authorization_code", fields["grant_type"])
            assertFalse(fields.containsKey("client_secret")); assertNull(request.header("Authorization"))
        }
        assertEquals("test-refresh", api.exchange(BikeyardEnvironment.SANDBOX, "code", "verifier").refreshToken)
    }

    @Test fun `upload explicitly sends visibility and external id and parses duplicate receipt`() = runBlocking {
        val job = BikeyardUpload("key", "ride", "SANDBOX:rider", BikeyardVisibility.PRIVATE, false,
            externalId = "nakvali-ride", name = "Ride", description = "", bikeType = "emtb")
        val file = File.createTempFile("bikeyard", ".tcx").apply { writeText("<test/>") }
        try {
            val api = api(409, """{"error":{"code":"duplicate_ride","message":"duplicate"},"upload":{"id":"upload","status":"duplicate","ride_id":null,"duplicate_of":null}}""") { request ->
                assertEquals("https://sandbox.yard.bike/v1/uploads", request.url.toString())
                assertEquals("Bearer access", request.header("Authorization"))
                val buffer = okio.Buffer(); request.body!!.writeTo(buffer); val body = buffer.readUtf8()
                assertTrue(body.contains("name=\"visibility\"\r\n\r\nprivate"))
                assertTrue(body.contains("name=\"external_id\"\r\n\r\nnakvali-ride"))
                assertTrue(body.contains("filename=\"nakvali.tcx\""))
                assertFalse(body.contains("trail_condition"))
            }
            val receipt = api.upload(BikeyardEnvironment.SANDBOX, "access", job, file)
            assertEquals("duplicate", receipt.status); assertNull(receipt.duplicateOf)
        } finally { file.delete() }
    }

    @Test fun `retry after handles seconds dates and long deadlines without retrying early`() {
        val now = 1000000L
        assertEquals(now + 120000, BikeyardApi.retryAt("120", now))
        assertEquals(now + 172800000, BikeyardApi.retryAt("172800", now))
        assertEquals(1445412480000, BikeyardApi.retryAt("Wed, 21 Oct 2015 07:28:00 GMT", now))
        assertEquals(now, BikeyardApi.retryAt("invalid", now))
    }

    @Test fun `token without write grant cannot be used for uploads`() {
        val tokens = BikeyardTokenResponse("access", "refresh", 21600, "Bearer", "rider", "profile:read rides:read")
        try { tokens.tokens(0); fail("Missing write scope must be rejected") }
        catch (error: BikeyardFailure) { assertEquals(BikeyardFailure.Kind.AUTH, error.kind) }
    }
}
