package com.nakvali.core.recording

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the on-device index entry encoding (`recordings.json`). Field and
 * status names are persisted on disk, so renames silently orphan existing
 * entries — if this test breaks, add a migration instead.
 */
class LocalRecordingTest {

    /** Same settings as [IndexJson] but compact, to pin field names readably. */
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `saved entry encodes all metadata fields`() {
        val entry = LocalRecording(
            id = "0b7f3a1e-1111-2222-3333-444455556666",
            startedAtMs = 1770000000000,
            endedAtMs = 1770000600000,
            sizeBytes = 123456,
            status = RecordingStatus.PENDING_UPLOAD,
            title = "Morning ride",
            description = "Loose and dusty",
            bikeId = "bike-1",
            bikeName = "Meta AM",
            bikeType = BikeType.FULL_SUS,
            savedAtMs = 1770000601000,
            serverId = "srv-42",
        )
        assertEquals(
            """{"id":"0b7f3a1e-1111-2222-3333-444455556666",""" +
                """"started_at_ms":1770000000000,"ended_at_ms":1770000600000,""" +
                """"size_bytes":123456,"status":"pending_upload",""" +
                """"title":"Morning ride","description":"Loose and dusty",""" +
                """"bike_id":"bike-1","bike_name":"Meta AM","bike_type":"full_sus",""" +
                """"saved_at_ms":1770000601000,"server_id":"srv-42"}""",
            json.encodeToString(entry),
        )
    }

    @Test
    fun `status wire names cover the whole lifecycle`() {
        assertEquals("\"recording\"", json.encodeToString(RecordingStatus.RECORDING))
        assertEquals("\"recorded\"", json.encodeToString(RecordingStatus.RECORDED))
        assertEquals("\"pending_upload\"", json.encodeToString(RecordingStatus.PENDING_UPLOAD))
        assertEquals("\"uploaded\"", json.encodeToString(RecordingStatus.UPLOADED))
        assertEquals("\"failed\"", json.encodeToString(RecordingStatus.FAILED))
    }

    @Test
    fun `active entry written at Start encodes id, start and status only`() {
        // This is the marker persisted the moment recording starts, so a
        // system kill can never make the ride invisible. No end/size yet.
        val entry = LocalRecording(
            id = "live-1",
            startedAtMs = 1770000000000,
            status = RecordingStatus.RECORDING,
        )
        assertEquals(
            """{"id":"live-1","started_at_ms":1770000000000,"status":"recording"}""",
            json.encodeToString(entry),
        )
    }

    @Test
    fun `recovered flag round-trips and defaults to false`() {
        val entry = json.decodeFromString<LocalRecording>(
            """{"id":"a","started_at_ms":1,"ended_at_ms":2,"size_bytes":3,"recovered":true}""",
        )
        assertEquals(true, entry.recovered)
        // Serialized when true…
        assertEquals(
            """{"id":"a","started_at_ms":1,"ended_at_ms":2,"size_bytes":3,"recovered":true}""",
            json.encodeToString(entry),
        )
        // …omitted when false, so pre-recovery indexes stay byte-compatible.
        val plain = json.decodeFromString<LocalRecording>(
            """{"id":"a","started_at_ms":1,"ended_at_ms":2,"size_bytes":3}""",
        )
        assertEquals(false, plain.recovered)
        assertEquals(
            """{"id":"a","started_at_ms":1,"ended_at_ms":2,"size_bytes":3}""",
            json.encodeToString(plain),
        )
    }

    @Test
    fun `failed recovery stays explicit and cannot be continued`() {
        val rawOnly = json.decodeFromString<LocalRecording>(
            """{"id":"a","started_at_ms":1,"ended_at_ms":2,"size_bytes":3,""" +
                """"recovered":true,"recovery_failed":true}""",
        )
        assertEquals(true, rawOnly.recoveryFailed)
        assertEquals(false, rawOnly.canContinueRecording())
        assertEquals(
            """{"id":"a","started_at_ms":1,"ended_at_ms":2,"size_bytes":3,""" +
                """"recovered":true,"recovery_failed":true}""",
            json.encodeToString(rawOnly),
        )
    }

    @Test
    fun `only readable unsaved recovered recording can continue`() {
        val recovered = LocalRecording(
            id = "a",
            startedAtMs = 1,
            endedAtMs = 2,
            sizeBytes = 3,
            recovered = true,
        )
        assertEquals(true, recovered.canContinueRecording())
        assertEquals(true, recovered.needsRecoveryAttention())
        assertEquals(false, recovered.copy(recovered = false).canContinueRecording())
        assertEquals(false, recovered.copy(savedAtMs = 3).canContinueRecording())
        assertEquals(
            false,
            recovered.copy(continuationAllowed = false).canContinueRecording(),
        )
        assertEquals(
            false,
            recovered.copy(continuationAllowed = false).needsRecoveryAttention(),
        )
        assertEquals(
            false,
            recovered.copy(status = RecordingStatus.PENDING_UPLOAD).canContinueRecording(),
        )
    }

    @Test
    fun `legacy entry without status decodes as recorded`() {
        // Pre-save-flow index shape (the old `uploaded` boolean is ignored).
        val entry = json.decodeFromString<LocalRecording>(
            """{"id":"a","started_at_ms":1,"ended_at_ms":2,"size_bytes":3,"uploaded":false}""",
        )
        assertEquals(RecordingStatus.RECORDED, entry.status)
        assertNull(entry.title)
        assertNull(entry.serverId)
        assertNull(entry.stravaExportStatus)
        assertNull(entry.stravaActivityId)
    }

    @Test
    fun `strava export state round-trips independently of raw upload`() {
        val entry = LocalRecording(
            id = "a",
            startedAtMs = 1,
            status = RecordingStatus.RECORDED,
            stravaExportStatus = StravaExportStatus.UPLOADED,
            stravaUploadId = 9001,
            stravaActivityId = 7002,
        )
        val encoded = json.encodeToString(entry)
        assertEquals(
            """{"id":"a","started_at_ms":1,"strava_export_status":"uploaded",""" +
                """"strava_upload_id":9001,"strava_activity_id":7002}""",
            encoded,
        )
        val decoded = json.decodeFromString<LocalRecording>(encoded)
        assertEquals(RecordingStatus.RECORDED, decoded.status)
        assertEquals(StravaExportStatus.UPLOADED, decoded.stravaExportStatus)
        assertEquals(7002L, decoded.stravaActivityId)
    }

    @Test
    fun `bikes file round-trips`() {
        val stored = BikesFile(
            bikes = listOf(Bike(id = "b1", name = "Meta AM", type = BikeType.EBIKE)),
            lastUsedId = "b1",
        )
        assertEquals(stored, json.decodeFromString<BikesFile>(json.encodeToString(stored)))
        assertEquals(
            """{"bikes":[{"id":"b1","name":"Meta AM","type":"ebike"}],"last_used_id":"b1"}""",
            json.encodeToString(stored),
        )
    }
}
