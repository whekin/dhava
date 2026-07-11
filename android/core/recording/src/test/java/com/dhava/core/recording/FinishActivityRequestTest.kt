package com.dhava.core.recording

import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the finish-request body shape — a contract with the backend, which
 * accepts the save metadata on POST /activities/{id}/finish. Field names and
 * `bike_type` values must match the server exactly; empty optional fields
 * are omitted, not sent as null.
 */
class FinishActivityRequestTest {

    private fun encode(request: FinishActivityRequest): String = ApiJson.encodeToString(request)

    @Test
    fun `finish with full metadata matches contract`() {
        val request = FinishActivityRequest(
            endedAtMs = 1770000600000,
            title = "Morning ride",
            description = "Loose and dusty",
            bike = "Meta AM",
            bikeType = BikeType.FULL_SUS,
        )
        assertEquals(
            """{"ended_at_ms":1770000600000,"title":"Morning ride",""" +
                """"description":"Loose and dusty","bike":"Meta AM","bike_type":"full_sus"}""",
            encode(request),
        )
    }

    @Test
    fun `finish omits empty optional fields`() {
        assertEquals(
            """{"ended_at_ms":1770000600000}""",
            encode(FinishActivityRequest(endedAtMs = 1770000600000)),
        )
    }

    @Test
    fun `bike_type wire values match the backend enum`() {
        assertEquals("\"full_sus\"", ApiJson.encodeToString(BikeType.FULL_SUS))
        assertEquals("\"hardtail\"", ApiJson.encodeToString(BikeType.HARDTAIL))
        assertEquals("\"ebike\"", ApiJson.encodeToString(BikeType.EBIKE))
        assertEquals("\"other\"", ApiJson.encodeToString(BikeType.OTHER))
    }
}
