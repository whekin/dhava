package com.nakvali.core.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingLocationPolicyTest {

    @Test
    fun `ride and transport policies both require direct gps`() {
        val ride = recordingLocationPolicy(powerSaving = false)
        val transport = recordingLocationPolicy(powerSaving = true)

        assertEquals("gps", ride.provider)
        assertEquals("gps", transport.provider)
    }

    @Test
    fun `transport saves power by cadence without changing source`() {
        val ride = recordingLocationPolicy(powerSaving = false)
        val transport = recordingLocationPolicy(powerSaving = true)

        assertEquals(1_000L, ride.intervalMs)
        assertEquals(500L, ride.minIntervalMs)
        assertEquals(5_000L, transport.intervalMs)
        assertEquals(2_500L, transport.minIntervalMs)
    }
}
