package com.nakvali.core.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StationaryImuPersistenceBufferTest {

    @Test
    fun `long still keeps two seconds and persists older samples at twenty hertz`() {
        val buffer = StationaryImuPersistenceBuffer()
        val emitted = mutableListOf<RecordLine.Imu>()

        for (timestampMs in 0L..10_000L step 5L) {
            emitted += buffer.accept(imu(timestampMs), stationary = true)
        }

        assertEquals(
            (0L..7_950L step 50L).toList(),
            emitted.map { it.timestampMs },
        )
        assertEquals(401, buffer.bufferedSampleCount)
    }

    @Test
    fun `sparse cadence retains enough samples for every replay stationary window`() {
        val buffer = StationaryImuPersistenceBuffer()
        val persisted = mutableListOf<RecordLine.Imu>()
        for (timestampMs in 0L..30_000L step 5L) {
            persisted += buffer.accept(imu(timestampMs), stationary = true)
        }
        persisted += buffer.flush()

        val timestamps = persisted.map { it.timestampMs }
        assertTrue(
            timestamps.zipWithNext().all { (previous, next) ->
                next - previous <= 50L
            },
        )
        for (windowStartMs in 0L..29_300L step 50L) {
            val samplesInWindow = timestamps.count { timestampMs ->
                timestampMs in windowStartMs..(windowStartMs + 700L)
            }
            assertTrue(
                "Only $samplesInWindow samples in replay window starting at $windowStartMs",
                samplesInWindow >= 12,
            )
        }
    }

    @Test
    fun `epoch offset and jittered near fifty millisecond input retain replay density`() {
        val buffer = StationaryImuPersistenceBuffer()
        val persisted = mutableListOf<RecordLine.Imu>()
        val sourceTimestamps = mutableListOf<Long>()
        val epochOffsetMs = 1_770_000_000_037L
        val sourceIntervalsMs = longArrayOf(49L, 47L, 52L, 50L, 48L, 51L, 46L)
        var intervalIndex = 0
        var timestampMs = epochOffsetMs
        val endMs = epochOffsetMs + 30_000L
        while (timestampMs <= endMs) {
            sourceTimestamps += timestampMs
            persisted += buffer.accept(imu(timestampMs), stationary = true)
            timestampMs += sourceIntervalsMs[intervalIndex % sourceIntervalsMs.size]
            intervalIndex++
        }
        persisted += buffer.flush()

        val timestamps = persisted.map { it.timestampMs }
        assertEquals(sourceTimestamps, timestamps)
        assertChronologicalAndUnique(timestamps)
        for (windowStartMs in epochOffsetMs..(endMs - 700L) step 50L) {
            val samplesInWindow = timestamps.count { timestamp ->
                timestamp in windowStartMs..(windowStartMs + 700L)
            }
            assertTrue(
                "Only $samplesInWindow samples in jittered window starting at $windowStartMs",
                samplesInWindow >= 12,
            )
        }
    }

    @Test
    fun `motion restores complete pre-roll before moving sample`() {
        val buffer = StationaryImuPersistenceBuffer()
        for (timestampMs in 0L..10_000L step 5L) {
            buffer.accept(imu(timestampMs), stationary = true)
        }

        val resumed = buffer.accept(imu(10_005L), stationary = false)

        assertEquals(
            (8_000L..10_005L step 5L).toList(),
            resumed.map { it.timestampMs },
        )
        assertEquals(0, buffer.bufferedSampleCount)
    }

    @Test
    fun `emitted records remain chronological and unique across transitions`() {
        val buffer = StationaryImuPersistenceBuffer()
        val emitted = mutableListOf<RecordLine.Imu>()

        for (timestampMs in 0L..500L step 5L) {
            emitted += buffer.accept(imu(timestampMs), stationary = false)
        }
        for (timestampMs in 505L..4_000L step 5L) {
            emitted += buffer.accept(imu(timestampMs), stationary = true)
        }
        for (timestampMs in 4_005L..4_500L step 5L) {
            emitted += buffer.accept(imu(timestampMs), stationary = false)
        }

        val timestamps = emitted.map { it.timestampMs }
        assertChronologicalAndUnique(timestamps)
    }

    @Test
    fun `short false still restores every sample`() {
        val buffer = StationaryImuPersistenceBuffer()
        val emitted = mutableListOf<RecordLine.Imu>()
        emitted += buffer.accept(imu(0L), stationary = false)
        for (timestampMs in 5L..1_500L step 5L) {
            emitted += buffer.accept(imu(timestampMs), stationary = true)
        }

        emitted += buffer.accept(imu(1_505L), stationary = false)

        assertEquals(
            (0L..1_505L step 5L).toList(),
            emitted.map { it.timestampMs },
        )
    }

    @Test
    fun `flush emits retained samples once and resets sparse cadence`() {
        val buffer = StationaryImuPersistenceBuffer()
        val emittedBeforeFlush = mutableListOf<RecordLine.Imu>()
        for (timestampMs in 0L..2_500L step 5L) {
            emittedBeforeFlush += buffer.accept(imu(timestampMs), stationary = true)
        }

        assertEquals(
            (0L..450L step 50L).toList(),
            emittedBeforeFlush.map { it.timestampMs },
        )
        assertEquals(
            (500L..2_500L step 5L).toList(),
            buffer.flush().map { it.timestampMs },
        )
        assertTrue(buffer.flush().isEmpty())

        val emittedAfterFlush = mutableListOf<RecordLine.Imu>()
        for (timestampMs in 2_505L..5_010L step 5L) {
            emittedAfterFlush += buffer.accept(imu(timestampMs), stationary = true)
        }
        assertEquals(2_505L, emittedAfterFlush.first().timestampMs)
        assertTrue(
            emittedAfterFlush
                .map { it.timestampMs }
                .zipWithNext()
                .all { (previous, next) -> next - previous in 1L..50L },
        )
    }

    @Test
    fun `duplicate and stale callbacks are never emitted`() {
        val buffer = StationaryImuPersistenceBuffer()

        val emitted = buildList {
            addAll(buffer.accept(imu(100L), stationary = false))
            addAll(buffer.accept(imu(100L), stationary = false))
            addAll(buffer.accept(imu(95L), stationary = false))
            addAll(buffer.accept(imu(105L), stationary = false))
        }

        assertEquals(listOf(100L, 105L), emitted.map { it.timestampMs })
    }

    @Test
    fun `reset drops old pre-roll and accepts a fresh session timeline`() {
        val buffer = StationaryImuPersistenceBuffer()
        buffer.accept(imu(10_000L), stationary = true)

        buffer.reset()

        assertEquals(0, buffer.bufferedSampleCount)
        assertEquals(
            listOf(1_000L),
            buffer.accept(imu(1_000L), stationary = false).map { it.timestampMs },
        )
    }

    private fun imu(timestampMs: Long) = RecordLine.Imu(
        timestampMs = timestampMs,
        accel = listOf(0f, 0f, 9.81f),
        gyro = listOf(0f, 0f, 0f),
    )

    private fun assertChronologicalAndUnique(timestamps: List<Long>) {
        assertEquals(timestamps.distinct(), timestamps)
        assertTrue(timestamps.zipWithNext().all { (previous, next) -> previous < next })
    }
}
