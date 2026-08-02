package com.nakvali.core.recording

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NakvaliBackupArchiveTest {
    @Test fun `round trip preserves raw authored and imported inputs`() {
        val root = Files.createTempDirectory("nakvali-backup-source").toFile()
        val raw = "raw-gps-imu-baro".encodeToByteArray()
        root.resolve("recordings/ride-1.jsonl.gz").apply {
            parentFile!!.mkdirs()
            writeBytes(raw)
        }
        root.resolve("recordings/ride-1.health.jsonl").writeText("{\"writer\":\"ok\"}\n")
        root.resolve("segments/segment-1.segment.json").apply {
            parentFile!!.mkdirs()
            writeText("{\"id\":\"segment-1\"}")
        }
        root.resolve("imported-traces/trace-1.gpx").apply {
            parentFile!!.mkdirs()
            writeText("<gpx/>")
        }
        root.resolve("imported-traces/trace-1.json").writeText("{\"id\":\"trace-1\"}")
        // Recomputable data must never inflate or become authoritative in a backup.
        root.resolve("activity-artifacts/ride-1.canonical.json.gz").apply {
            parentFile!!.mkdirs()
            writeText("derived")
        }
        val recordings = listOf(LocalRecording(id = "ride-1", startedAtMs = 1L))
        val bikes = BikesFile(listOf(Bike("bike-1", "Capra", BikeType.FULL_SUS)), "bike-1")
        val bytes = ByteArrayOutputStream()
        val archive = NakvaliBackupArchive(root)

        val summary = archive.write(
            output = bytes,
            recordingsJson = IndexJson.encodeToString(recordings).encodeToByteArray(),
            bikesJson = IndexJson.encodeToString(bikes).encodeToByteArray(),
            createdAtMs = 123L,
        )

        assertEquals(1, summary.preview.recordingCount)
        assertEquals(1, summary.preview.segmentCount)
        assertEquals(1, summary.preview.importedTraceCount)
        assertEquals(summary.preview, archive.inspect(ByteArrayInputStream(bytes.toByteArray())))

        val staging = Files.createTempDirectory("nakvali-backup-target").toFile().resolve("staging")
        val restored = archive.extract(ByteArrayInputStream(bytes.toByteArray()), staging)
        assertArrayEquals(raw, restored.file("recordings/ride-1.jsonl.gz").readBytes())
        assertTrue(restored.file("segments/segment-1.segment.json").isFile)
        assertTrue(restored.file("imported-traces/trace-1.gpx").isFile)
        assertTrue(!staging.resolve("activity-artifacts").exists())
    }

    @Test fun `changed payload fails checksum before restore`() {
        val root = Files.createTempDirectory("nakvali-backup-corrupt").toFile()
        val payload = "uniquely-identifiable-raw-payload".encodeToByteArray()
        root.resolve("recordings/ride.jsonl.gz").apply {
            parentFile!!.mkdirs()
            writeBytes(payload)
        }
        val bytes = ByteArrayOutputStream().also { output ->
            NakvaliBackupArchive(root).write(
                output,
                IndexJson.encodeToString(listOf(LocalRecording("ride", 1L))).encodeToByteArray(),
                IndexJson.encodeToString(BikesFile()).encodeToByteArray(),
                1L,
            )
        }.toByteArray()
        val offset = bytes.indexOf(payload)
        assertTrue(offset >= 0)
        bytes[offset] = (bytes[offset].toInt() xor 1).toByte()

        val result = runCatching {
            NakvaliBackupArchive(root).extract(
                ByteArrayInputStream(bytes),
                Files.createTempDirectory("nakvali-backup-corrupt-target").toFile().resolve("staging"),
            )
        }
        assertTrue(result.exceptionOrNull() is NakvaliBackupException)
    }

    @Test fun `non Nakvali zip is rejected from its first entry`() {
        val bytes = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("other.txt"))
                zip.write("not a backup".encodeToByteArray())
                zip.closeEntry()
            }
        }.toByteArray()

        val result = runCatching {
            NakvaliBackupArchive(Files.createTempDirectory("nakvali-not-backup").toFile())
                .inspect(ByteArrayInputStream(bytes))
        }
        assertTrue(result.exceptionOrNull() is NakvaliBackupException)
    }

    private fun ByteArray.indexOf(needle: ByteArray): Int {
        if (needle.isEmpty()) return 0
        for (index in 0..size - needle.size) {
            var matches = true
            for (offset in needle.indices) {
                if (this[index + offset] != needle[offset]) {
                    matches = false
                    break
                }
            }
            if (matches) return index
        }
        return -1
    }
}
