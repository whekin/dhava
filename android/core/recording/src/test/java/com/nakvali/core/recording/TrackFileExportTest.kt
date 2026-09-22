package com.nakvali.core.recording

import java.nio.file.Files
import java.util.zip.GZIPInputStream
import org.junit.Assert.*
import org.junit.Test

class TrackFileExportTest {
    @Test fun `one Hz keeps every run and gap endpoint with original odometer`() {
        val points = (0..15).map { index ->
            TrackExportPoint(index * 200L, 41.7, 44.8, sectionId = if (index < 7) 0 else 1,
                runId = if (index < 12) 0 else 1, odometerM = index * .73)
        }
        val result = TrackFileExport.sample(points, TrackExportRate.ONE_HZ)
        assertEquals(listOf(0L, 1000, 1200, 1400, 2200, 2400, 3000), result.map { it.timestampMs })
        assertTrue(result.all { it in points })
        assertEquals(points.last().odometerM, result.last().odometerM, 0.0)
        assertEquals(points, TrackFileExport.sample(points, TrackExportRate.FIVE_HZ))
    }

    @Test fun `sub second runs survive one Hz sampling`() {
        val points = listOf(
            TrackExportPoint(0, 41.7, 44.8, runId = 0),
            TrackExportPoint(200, 41.7, 44.8, runId = 0),
            TrackExportPoint(400, 41.7, 44.8, runId = 1),
            TrackExportPoint(600, 41.7, 44.8, runId = 1),
        )
        assertEquals(points, TrackFileExport.sample(points, TrackExportRate.ONE_HZ))
    }

    @Test fun `gzip round trips GPX TCX and FIT without altering source bytes`() {
        val directory = Files.createTempDirectory("nakvali-gzip").toFile()
        val points = listOf(TrackExportPoint(1_770_000_000_200, 41.7, 44.8, 1000.5))
        try {
            TrackFileFormat.entries.forEach { format ->
                val original = TrackFileExport.write(points, "Ride & trail", java.io.File(directory, "ride.${format.extension}"), format)
                val bytes = original.readBytes()
                val compressed = TrackFileExport.gzip(original)
                assertEquals("ride.${format.extension}.gz", compressed.name)
                assertArrayEquals(bytes, GZIPInputStream(compressed.inputStream()).use { it.readBytes() })
                assertArrayEquals(bytes, original.readBytes())
            }
        } finally { directory.deleteRecursively() }
    }
}
