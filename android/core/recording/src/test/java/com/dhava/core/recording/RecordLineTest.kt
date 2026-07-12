package com.dhava.core.recording

import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the JSONL line encoding to `proto/raw-recording-format.md` v1.
 * Field names and the `type` discriminator are a hard contract with the
 * serde types in `fusion/crates/fusion-core` — if this test breaks, the
 * format (not the test) is probably being broken.
 */
class RecordLineTest {

    private fun encode(line: RecordLine): String = RecordLineJson.encodeToString(line)

    @Test
    fun `meta line matches spec`() {
        val line = RecordLine.Meta(
            activityId = "0b7f3a1e-1111-2222-3333-444455556666",
            device = "Pixel 8",
            os = "android-15",
            appVersion = "0.1.0",
            startedAtMs = 1770000000000,
        )
        assertEquals(
            """{"type":"meta","version":1,""" +
                """"activity_id":"0b7f3a1e-1111-2222-3333-444455556666",""" +
                """"device":"Pixel 8","os":"android-15","app_version":"0.1.0",""" +
                """"started_at_ms":1770000000000}""",
            encode(line),
        )
    }

    @Test
    fun `gps line matches spec`() {
        val line = RecordLine.Gps(
            timestampMs = 1770000001000,
            lat = 41.7151,
            lon = 44.8271,
            altitudeM = 712.4,
            accuracyM = 3.9,
            speedMps = 8.2,
            bearingDeg = 184.0,
        )
        assertEquals(
            """{"type":"gps","timestamp_ms":1770000001000,"lat":41.7151,"lon":44.8271,""" +
                """"altitude_m":712.4,"accuracy_m":3.9,"speed_mps":8.2,"bearing_deg":184.0}""",
            encode(line),
        )
    }

    @Test
    fun `gps line omits unavailable optional fields`() {
        val line = RecordLine.Gps(timestampMs = 1770000001000, lat = 41.7151, lon = 44.8271)
        assertEquals(
            """{"type":"gps","timestamp_ms":1770000001000,"lat":41.7151,"lon":44.8271}""",
            encode(line),
        )
    }

    @Test
    fun `imu line matches spec`() {
        val line = RecordLine.Imu(
            timestampMs = 1770000001005,
            accel = listOf(0.12f, -0.03f, 9.79f),
            gyro = listOf(0.01f, 0.0f, -0.02f),
            mag = listOf(22.1f, -4.3f, 41.0f),
        )
        assertEquals(
            """{"type":"imu","timestamp_ms":1770000001005,"accel":[0.12,-0.03,9.79],""" +
                """"gyro":[0.01,0.0,-0.02],"mag":[22.1,-4.3,41.0]}""",
            encode(line),
        )
    }

    @Test
    fun `imu line omits mag when device has no magnetometer`() {
        val line = RecordLine.Imu(
            timestampMs = 1770000001005,
            accel = listOf(0.12f, -0.03f, 9.79f),
            gyro = listOf(0.01f, 0.0f, -0.02f),
        )
        assertEquals(
            """{"type":"imu","timestamp_ms":1770000001005,""" +
                """"accel":[0.12,-0.03,9.79],"gyro":[0.01,0.0,-0.02]}""",
            encode(line),
        )
    }

    @Test
    fun `baro line matches spec`() {
        val line = RecordLine.Baro(timestampMs = 1770000001010, pressureHpa = 934.2f)
        assertEquals(
            """{"type":"baro","timestamp_ms":1770000001010,"pressure_hpa":934.2}""",
            encode(line),
        )
    }

    @Test
    fun `pause event matches spec`() {
        assertEquals(
            """{"type":"event","timestamp_ms":1770000002000,"action":"pause"}""",
            encode(RecordLine.Event(1770000002000, "pause")),
        )
    }
}
