package com.dhava.core.recording

import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecordingHealthTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `health log appends durable json lines and tolerates a damaged tail`() {
        val file = tmp.newFile("ride.health.jsonl")
        val log = RecordingHealthLog(file)
        log.append(
            RecordingHealthEntry(
                timestampMs = 1_000,
                kind = RecordingHealthLog.KIND_START,
                sessionElapsedMs = 0,
                pssKb = 120_000,
            ),
        )
        log.append(
            RecordingHealthEntry(
                timestampMs = 61_000,
                kind = RecordingHealthLog.KIND_HEARTBEAT,
                sessionElapsedMs = 60_000,
                writerPendingImu = 12,
            ),
        )
        file.appendText("""{"timestamp_ms":""")
        log.append(
            RecordingHealthEntry(
                timestampMs = 62_000,
                kind = RecordingHealthLog.KIND_STOP,
                sessionElapsedMs = 61_000,
            ),
        )

        val entries = log.entries()

        assertEquals(3, entries.size)
        assertEquals(120_000L, entries.first().pssKb)
        assertEquals(12, entries[1].writerPendingImu)
        assertEquals(RecordingHealthLog.KIND_STOP, entries.last().kind)
    }

    @Test
    fun `process exit is deduplicated by system timestamp`() {
        val log = RecordingHealthLog(tmp.newFile("exit.health.jsonl"))
        val exit = RecordingHealthEntry(
            timestampMs = 42_000,
            kind = RecordingHealthLog.KIND_PROCESS_EXIT,
            exitTimestampMs = 42_000,
            exitReason = 13,
            exitDescription = "o-kill(6)",
        )
        log.append(exit)

        assertTrue(log.hasProcessExit(42_000))
        assertFalse(log.hasProcessExit(43_000))
    }

    @Test
    fun `optional health fields stay absent instead of becoming fake zeroes`() {
        val encoded = RecordingHealthJson.encodeToString(
            RecordingHealthEntry(
                timestampMs = 1_000,
                kind = RecordingHealthLog.KIND_PROCESS_EXIT,
                exitReason = 13,
            ),
        )

        assertTrue(""""exit_reason":13""" in encoded)
        assertFalse("pss_kb" in encoded)
        assertFalse("battery_percent" in encoded)
    }
}
