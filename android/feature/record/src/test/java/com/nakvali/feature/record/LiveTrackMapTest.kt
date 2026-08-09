package com.nakvali.feature.record

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTrackMapTest {
    @Test
    fun `map gesture cannot cancel following before first location is focused`() {
        assertFalse(
            shouldStopFollowingForCameraGesture(
                isApiGesture = true,
                initialLocationApplied = false,
            ),
        )
    }

    @Test
    fun `map gesture cancels following after first location is focused`() {
        assertTrue(
            shouldStopFollowingForCameraGesture(
                isApiGesture = true,
                initialLocationApplied = true,
            ),
        )
    }
}
