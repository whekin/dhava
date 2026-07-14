package com.dhava.core.recording

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GpxExporterTest {
    @Test fun `exports timestamps elevation and escaped title`() {
        val file = Files.createTempFile("dhava", ".gpx").toFile()
        GpxExporter.write(
            listOf(GpxTrackPoint(1_770_000_001_000, 41.7, 44.8, altitudeM = 712.4)),
            "Ride & trail",
            file,
        )
        val xml = file.readText()
        assertTrue(xml.contains("<name>Ride &amp; trail</name>"))
        assertTrue(xml.contains("<trkpt lat=\"41.7\" lon=\"44.8\">"))
        assertTrue(xml.contains("<ele>712.4</ele>"))
        assertTrue(xml.contains("<time>2026-02-02T02:40:01Z</time>"))
    }

    @Test fun `keeps 5 Hz timestamps and separates pause sections`() {
        val file = Files.createTempFile("dhava-sections", ".gpx").toFile()
        GpxExporter.write(
            listOf(
                GpxTrackPoint(1_770_000_001_000, 41.70, 44.80, sectionId = 0),
                GpxTrackPoint(1_770_000_001_200, 41.71, 44.81, sectionId = 0),
                GpxTrackPoint(1_770_000_010_000, 41.80, 44.90, sectionId = 1),
            ),
            "Paused ride",
            file,
        )

        val xml = file.readText()
        assertEquals(2, "<trkseg>".toRegex().findAll(xml).count())
        assertEquals(2, "</trkseg>".toRegex().findAll(xml).count())
        assertTrue(xml.contains("<time>2026-02-02T02:40:01.200Z</time>"))
        assertTrue(xml.indexOf("</trkseg>") < xml.indexOf("lat=\"41.8\""))
    }
}
