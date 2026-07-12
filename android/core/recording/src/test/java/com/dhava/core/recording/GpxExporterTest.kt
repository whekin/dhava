package com.dhava.core.recording

import java.nio.file.Files
import org.junit.Assert.assertTrue
import org.junit.Test

class GpxExporterTest {
    @Test fun `exports timestamps elevation and escaped title`() {
        val file = Files.createTempFile("dhava", ".gpx").toFile()
        GpxExporter.write(
            listOf(RecordLine.Gps(1_770_000_001_000, 41.7, 44.8, altitudeM = 712.4)),
            "Ride & trail",
            file,
        )
        val xml = file.readText()
        assertTrue(xml.contains("<name>Ride &amp; trail</name>"))
        assertTrue(xml.contains("<trkpt lat=\"41.7\" lon=\"44.8\">"))
        assertTrue(xml.contains("<ele>712.4</ele>"))
        assertTrue(xml.contains("<time>2026-02-02T02:40:01Z</time>"))
    }
}
