package com.nakvali.feature.activity

import com.nakvali.core.recording.StoredTransportEpisode
import org.junit.Assert.*
import org.junit.Test

class TransportEditorStateTest {
    @Test fun `unchanged clock fields retain exact millisecond boundaries`() {
        val origin = 1_789_000_000_000L
        val interval = StoredTransportEpisode(origin + 10_125, origin + 90_875)
        val draft = interval.draft(origin)
        assertEquals(interval, draft.interval(origin))
        assertEquals(origin + 11_000, draft.copy(start = "00:00:11").interval(origin).startedAtMs)
        assertEquals(interval.endedAtMs, draft.copy(start = "00:00:11").interval(origin).endedAtMs)
    }

    @Test fun `elapsed clock handles overnight rides and rejects malformed values`() {
        assertEquals(90_061_000L, parseElapsedClock("25:01:01"))
        assertEquals("25:01:01", elapsedClock(90_061_000))
        for (value in listOf("", "12:99:00", "01:00", "-1:00:00", "abc")) {
            assertThrows(IllegalArgumentException::class.java) { parseElapsedClock(value) }
        }
    }
}
