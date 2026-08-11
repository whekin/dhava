package com.nakvali.feature.record

import com.nakvali.core.recording.LocalRecording
import com.nakvali.core.recording.RecordingStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivitiesScreenTest {
    @Test
    fun `finalized but unsaved recording keeps the save action`() {
        assertTrue(recording(savedAtMs = null).needsSaveAction())
    }

    @Test
    fun `saved offline recording is shown as local instead of asking to save again`() {
        assertFalse(recording(savedAtMs = 2_000).needsSaveAction())
    }

    @Test
    fun `queued and uploaded recordings never show the save action`() {
        assertFalse(
            recording(savedAtMs = 2_000, status = RecordingStatus.PENDING_UPLOAD)
                .needsSaveAction(),
        )
        assertFalse(
            recording(savedAtMs = 2_000, status = RecordingStatus.UPLOADED)
                .needsSaveAction(),
        )
    }

    private fun recording(
        savedAtMs: Long?,
        status: RecordingStatus = RecordingStatus.RECORDED,
    ) = LocalRecording(
        id = "ride",
        startedAtMs = 1_000,
        endedAtMs = 2_000,
        status = status,
        savedAtMs = savedAtMs,
    )
}
