package com.dhava.core.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingNotificationTest {
    @Test
    fun `formats elapsed time beyond one hour`() {
        assertEquals("02:03:04", formatNotificationElapsed(7_384_999L))
    }

    @Test
    fun `preparing and recovering never expose a recording action`() {
        assertEquals(
            RecordingNotificationPresentation(
                title = "Preparing ride",
                text = "Warming GPS and motion sensors",
                action = null,
            ),
            presentation(preparing = true),
        )
        assertEquals(
            RecordingNotificationPresentation(
                title = "Restoring interrupted ride",
                text = "Your recorded data is safe",
                action = null,
            ),
            presentation(preparing = true, recovering = true),
        )
    }

    @Test
    fun `active restored and paused rides expose only safe actions`() {
        assertEquals(
            RecordingNotificationAction.Pause,
            presentation().action,
        )
        assertEquals(
            RecordingNotificationPresentation(
                title = "Ride restored — 00:01:05",
                text = "Recording resumed after interruption",
                action = RecordingNotificationAction.Pause,
            ),
            presentation(recentlyRecovered = true),
        )
        assertEquals(
            RecordingNotificationPresentation(
                title = "Ride paused — 00:01:05",
                text = "GPS and motion capture paused",
                action = RecordingNotificationAction.Resume,
            ),
            presentation(paused = true, recentlyRecovered = true),
        )
    }

    @Test
    fun `recovery notice has a bounded monotonic window`() {
        assertEquals(false, isRecoveryNotificationActive(Long.MIN_VALUE, 100_000L))
        assertEquals(false, isRecoveryNotificationActive(100_000L, 99_999L))
        assertEquals(true, isRecoveryNotificationActive(100_000L, 129_999L))
        assertEquals(false, isRecoveryNotificationActive(100_000L, 130_000L))
    }

    private fun presentation(
        preparing: Boolean = false,
        recovering: Boolean = false,
        paused: Boolean = false,
        recentlyRecovered: Boolean = false,
    ): RecordingNotificationPresentation = recordingNotificationPresentation(
        elapsedMs = 65_000L,
        preparing = preparing,
        recovering = recovering,
        paused = paused,
        recentlyRecovered = recentlyRecovered,
    )
}
