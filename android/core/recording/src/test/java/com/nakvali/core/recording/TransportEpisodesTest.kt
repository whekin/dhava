package com.nakvali.core.recording

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class TransportEpisodesTest {
    @Test fun `recording index preserves automatic explicit empty and authored intervals`() {
        val codec = Json { encodeDefaults = true }
        val legacy = codec.decodeFromString<LocalRecording>("""{"id":"ride","started_at_ms":1000}""")
        assertNull(legacy.transportEpisodes)
        assertEquals(0L, legacy.transportRevision)
        for (episodes in listOf(emptyList(), listOf(StoredTransportEpisode(2_000, 8_000)))) {
            val authored = legacy.copy(transportEpisodes = episodes, transportRevision = 7)
            assertEquals(authored, codec.decodeFromString<LocalRecording>(codec.encodeToString(authored)))
        }
    }
}
