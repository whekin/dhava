package com.nakvali.core.recording

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RideBoundsAnnotationTest {
    @Test fun `recording index round-trips an absent and an authored trim`() {
        val codec = Json { encodeDefaults = true }
        val legacy = codec.decodeFromString<LocalRecording>(
            """{"id":"ride","started_at_ms":1000,"ended_at_ms":9000}""",
        )
        assertNull(legacy.rideBounds)

        val trimmed = legacy.copy(rideBounds = StoredRideBounds(3_000, 7_000))
        assertEquals(trimmed, codec.decodeFromString<LocalRecording>(codec.encodeToString(trimmed)))
    }

    /**
     * The recording's own bounds are load-bearing for continuation, crash
     * recovery, backup validation and list ordering, so a trim must never move
     * them — it only changes what the app calls the ride.
     */
    @Test fun `a trim narrows the riding span and leaves the recording bounds alone`() {
        val whole = LocalRecording(id = "ride", startedAtMs = 1_000, endedAtMs = 9_000)
        assertEquals(1_000, whole.ridingStartedAtMs)
        assertEquals(9_000, whole.ridingEndedAtMs)
        assertEquals(8_000, whole.ridingDurationMs)

        val trimmed = whole.copy(rideBounds = StoredRideBounds(3_000, 7_000))
        assertEquals(3_000, trimmed.ridingStartedAtMs)
        assertEquals(7_000, trimmed.ridingEndedAtMs)
        assertEquals(4_000, trimmed.ridingDurationMs)
        assertEquals(1_000, trimmed.startedAtMs)
        assertEquals(9_000, trimmed.endedAtMs)
    }
}
