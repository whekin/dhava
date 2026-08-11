package com.nakvali.core.recording

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
    fun `ride totals replace the reassurance line once the ride has moved`() {
        assertEquals(
            "8.2 km · −612 m",
            presentation(distanceM = 8_240.0, descentM = 612.0).text,
        )
        assertEquals(
            "840 m · −0 m",
            presentation(distanceM = 840.0).text,
        )
        assertEquals(
            "8.2 km · −612 m · capture paused",
            presentation(paused = true, distanceM = 8_240.0, descentM = 612.0).text,
        )
    }

    @Test
    fun `a ride that has not moved keeps the plain recording line`() {
        assertEquals("GPS and motion capture active", presentation().text)
        assertEquals(
            "GPS and motion capture active",
            presentation(distanceM = 0.4, descentM = 0.9).text,
        )
    }

    @Test
    fun `a finished segment run takes the collapsed line and keeps the totals`() {
        val presentation = presentation(
            distanceM = 8_240.0,
            descentM = 612.0,
            lastRun = run(elapsedMs = 203_600, deltaMs = -2_100, personalRecord = true),
        )
        assertEquals("Ridgeline 3:23.6 · PR · −2.1 s", presentation.text)
        assertEquals("Ridgeline 3:23.6 · PR · −2.1 s\n8.2 km · −612 m", presentation.expandedText)
    }

    @Test
    fun `a slower run reports a positive delta and no record`() {
        assertEquals(
            "Ridgeline 3:25.0 · +4.0 s",
            presentation(lastRun = run(elapsedMs = 205_000, deltaMs = 4_000)).text,
        )
    }

    @Test
    fun `a run without totals needs no expanded line`() {
        val presentation = presentation(lastRun = run(elapsedMs = 205_000, deltaMs = null))
        assertEquals("Ridgeline 3:25.0", presentation.text)
        assertEquals(null, presentation.expandedText)
    }

    @Test
    fun `transport power saving is stated rather than silently coarsening`() {
        assertEquals(
            "Transport · saving power",
            presentation(powerSaving = true).text,
        )
        assertEquals(
            "8.2 km · −612 m · transport",
            presentation(powerSaving = true, distanceM = 8_240.0, descentM = 612.0).text,
        )
    }

    @Test
    fun `a paused ride never claims to be saving power`() {
        assertEquals(
            "GPS and motion capture paused",
            presentation(paused = true, powerSaving = true).text,
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
        distanceM: Double = 0.0,
        descentM: Double = 0.0,
        lastRun: LiveSegmentRun? = null,
        powerSaving: Boolean = false,
    ): RecordingNotificationPresentation = recordingNotificationPresentation(
        elapsedMs = 65_000L,
        preparing = preparing,
        recovering = recovering,
        paused = paused,
        recentlyRecovered = recentlyRecovered,
        distanceM = distanceM,
        descentM = descentM,
        lastRun = lastRun,
        powerSaving = powerSaving,
    )

    private fun run(
        elapsedMs: Long,
        deltaMs: Long?,
        personalRecord: Boolean = false,
    ): LiveSegmentRun = LiveSegmentRun(
        segmentId = "seg",
        name = "Ridgeline",
        finishedAtMs = 1_780_000_000_000,
        elapsedMs = elapsedMs,
        deltaMs = deltaMs,
        personalRecord = personalRecord,
    )
}
