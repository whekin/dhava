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
            assertEquals("https://open.yard.bike/oauth/token", request.url.toString())
            val body = request.body as FormBody
            val fields = (0 until body.size).associate { body.name(it) to body.value(it) }
            assertEquals(BikeyardEnvironment.LIVE.clientId, fields["client_id"])
            assertEquals(BikeyardPkce.REDIRECT_URI, fields["redirect_uri"])
            assertEquals("verifier", fields["code_verifier"])
            assertEquals("authorization_code", fields["grant_type"])
            assertFalse(fields.containsKey("client_secret")); assertNull(request.header("Authorization"))
        }
        assertEquals("test-refresh", api.exchange(BikeyardEnvironment.LIVE, "code", "verifier").refreshToken)
    }

    @Test fun `upload explicitly sends visibility and external id and parses duplicate receipt`() = runBlocking {
        val job = BikeyardUpload("key", "ride", "LIVE:rider", BikeyardVisibility.PRIVATE, false,
            externalId = "nakvali-ride", name = "Ride", description = "", bikeType = "emtb")
        val file = File.createTempFile("bikeyard", ".tcx").apply { writeText("<test/>") }
        try {
            val api = api(409, """{"error":{"code":"duplicate_ride","message":"duplicate"},"upload":{"id":"upload","status":"duplicate","ride_id":null,"duplicate_of":null}}""") { request ->
                assertEquals("https://open.yard.bike/v1/uploads", request.url.toString())
                assertEquals("Bearer access", request.header("Authorization"))
                assertEquals("respond-async", request.header("Prefer"))
                val multipart = request.body as okhttp3.MultipartBody
                val filePart = multipart.parts.first().body
                assertEquals("application/gzip", filePart.contentType().toString())
                val compressed = okio.Buffer().also { filePart.writeTo(it) }.readByteArray()
                assertEquals("<test/>", java.util.zip.GZIPInputStream(compressed.inputStream()).reader().readText())
                val buffer = okio.Buffer(); request.body!!.writeTo(buffer); val body = buffer.readUtf8()
                assertTrue(body.contains("name=\"visibility\"\r\n\r\nprivate"))
                assertTrue(body.contains("name=\"external_id\"\r\n\r\nnakvali-ride"))
                assertTrue(body.contains("filename=\"nakvali.tcx.gz\""))
                assertFalse(body.contains("trail_condition"))
            }
            val receipt = api.upload(BikeyardEnvironment.LIVE, "access", job, file)
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
    @Test fun `async acceptance is decoded and gzip temporary is removed`() = runBlocking {
        val file = File.createTempFile("bikeyard-async", ".tcx").apply { writeText("<test/>") }
        val job = BikeyardUpload("key", "ride", "LIVE:rider", BikeyardVisibility.PRIVATE, false,
            externalId = "nakvali-ride", name = "Ride", description = "", bikeType = "mtb")
        try {
            val api = api(202, """{"id":"upload","status":"processing","ride_id":null}""") {
                assertEquals("respond-async", it.header("Prefer"))
            }
            assertEquals("processing", api.upload(BikeyardEnvironment.LIVE, "access", job, file).status)
            assertEquals("<test/>", file.readText())
            assertFalse(File(file.parentFile, "${file.name}.gz").exists())
        } finally { file.delete() }
    }

    @Test fun `metrics PUT sends frozen JSON and accepts an idempotent 200`() = runBlocking {
        val rideId = "11111111-1111-1111-1111-111111111111"
        val file = File.createTempFile("bikeyard-metrics", ".json").apply {
            writeText("""{"schema":"bikeyard.sensor-metrics","events":[]}""")
        }
        try {
            val api = api(200, """{"ride_id":"$rideId","revision":2}""") { request ->
                assertEquals("https://open.yard.bike/v1/rides/$rideId/sensor-metrics", request.url.toString())
                assertEquals("PUT", request.method)
                assertEquals("Bearer access", request.header("Authorization"))
                assertEquals("application/json", request.body!!.contentType().toString())
                val body = okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                assertEquals(file.readText(), body)
            }
            assertEquals(2, api.putMetrics(BikeyardEnvironment.LIVE, "access", rideId, file).revision)
        } finally { file.delete() }
    }

    @Test fun `ride-level metrics denial does not revoke an otherwise valid connection`() = runBlocking {
        val rideId = "11111111-1111-1111-1111-111111111111"
        val file = File.createTempFile("bikeyard-metrics", ".json").apply { writeText("{}") }
        try {
            val api = api(403, """{"error":{"code":"forbidden","message":"not yours"}}""") {}
            val result = runCatching { api.putMetrics(BikeyardEnvironment.LIVE, "access", rideId, file) }
            assertEquals(BikeyardFailure.Kind.PERMANENT, (result.exceptionOrNull() as BikeyardFailure).kind)
        } finally { file.delete() }
    }

}
