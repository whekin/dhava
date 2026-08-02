package com.nakvali.core.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Behavior of [withMetadata], the single transform behind both the save sheet
 * and the post-save edit dialog (RecordingRepository.saveActivity /
 * updateMetadata) — the two paths must never drift apart.
 */
class LocalRecordingMetadataTest {

    private val saved = LocalRecording(
        id = "ride-1",
        startedAtMs = 1_770_000_000_000,
        endedAtMs = 1_770_000_600_000,
        sizeBytes = 42,
        status = RecordingStatus.UPLOADED,
        title = "Old title",
        description = "Old notes",
        bikeId = "bike-old",
        bikeName = "Old bike",
        bikeType = BikeType.HARDTAIL,
        savedAtMs = 1_770_000_601_000,
        serverId = "srv-1",
    )

    @Test
    fun `trims fields and replaces the bike`() {
        val bike = Bike(id = "bike-new", name = "Meta AM", type = BikeType.FULL_SUS)
        val edited = saved.withMetadata("  New title ", " Fresh notes ", bike)
        assertEquals("New title", edited.title)
        assertEquals("Fresh notes", edited.description)
        assertEquals("bike-new", edited.bikeId)
        assertEquals("Meta AM", edited.bikeName)
        assertEquals(BikeType.FULL_SUS, edited.bikeType)
    }

    @Test
    fun `blank fields and a null bike clear the metadata`() {
        val edited = saved.withMetadata("   ", "", null)
        assertNull(edited.title)
        assertNull(edited.description)
        assertNull(edited.bikeId)
        assertNull(edited.bikeName)
        assertNull(edited.bikeType)
    }

    @Test
    fun `editing metadata never touches lifecycle fields`() {
        val edited = saved.withMetadata("New title", "notes", null)
        assertEquals(saved.status, edited.status)
        assertEquals(saved.savedAtMs, edited.savedAtMs)
        assertEquals(saved.serverId, edited.serverId)
        assertEquals(saved.startedAtMs, edited.startedAtMs)
        assertEquals(saved.endedAtMs, edited.endedAtMs)
        assertEquals(saved.sizeBytes, edited.sizeBytes)
    }
}
