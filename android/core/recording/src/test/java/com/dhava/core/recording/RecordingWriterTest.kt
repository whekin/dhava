package com.dhava.core.recording

import java.util.zip.GZIPInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecordingWriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `bounded IMU queue still drains critical rows and closes valid gzip`() = runBlocking {
        val file = tmp.newFile("ride.jsonl.gz")
        val writer = RecordingWriter(file, imuQueueCapacity = 1)
        writer.write(
            RecordLine.Meta(
                activityId = "test",
                device = "OnePlus",
                os = "android-16",
                appVersion = "test",
                startedAtMs = 1_000,
            ),
        )
        repeat(20_000) { index ->
            writer.write(
                RecordLine.Imu(
                    timestampMs = 1_000L + index,
                    accel = listOf(0f, 0f, 9.8f),
                    gyro = listOf(0f, 0f, 0f),
                ),
            )
        }
        writer.write(
            RecordLine.Gps(
                timestampMs = 21_000,
                lat = 41.7,
                lon = 44.8,
                accuracyM = 4.0,
            ),
        )

        writer.close()
        val health = writer.healthStats()

        val lines = GZIPInputStream(file.inputStream())
            .bufferedReader()
            .use { it.readLines() }
        assertTrue(lines.any { "\"type\":\"meta\"" in it })
        assertTrue(lines.any { "\"type\":\"gps\"" in it })
        assertTrue(lines.any { "\"type\":\"imu\"" in it })
        assertTrue(lines.any { "\"action\":\"imu_overflow:" in it })
        assertEquals(0, health.pendingCritical)
        assertEquals(0, health.pendingImu)
        assertTrue(health.droppedImuTotal > 0)
    }
}
