package com.dhava.core.recording

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackendAccessTest {
    @Test
    fun `configured key is added without touching authorization`() {
        val request = Request.Builder()
            .url("https://api.example.com/api/v1/strava/connection")
            .header("Authorization", "Bearer device-token")
            .withDhavaAccessKey("alpha-key")
            .build()

        assertEquals("alpha-key", request.header(DHAVA_ACCESS_KEY_HEADER))
        assertEquals("Bearer device-token", request.header("Authorization"))
    }

    @Test
    fun `blank key is omitted for local development`() {
        val request = Request.Builder()
            .url("http://127.0.0.1:8080/healthz")
            .withDhavaAccessKey("  ")
            .build()

        assertNull(request.header(DHAVA_ACCESS_KEY_HEADER))
    }
}
