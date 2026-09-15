package com.nakvali.core.recording

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GpxExporterTest {
    @Test fun `exports timestamps elevation and escaped title`() {
        val file = Files.createTempFile("nakvali", ".gpx").toFile()
        GpxExporter.write(
            listOf(TrackExportPoint(1_770_000_001_000, 41.7, 44.8, altitudeM = 712.4)),
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
        val file = Files.createTempFile("nakvali-sections", ".gpx").toFile()
        GpxExporter.write(
            listOf(
                TrackExportPoint(1_770_000_001_000, 41.70, 44.80, sectionId = 0),
                TrackExportPoint(1_770_000_001_200, 41.71, 44.81, sectionId = 0),
                TrackExportPoint(1_770_000_010_000, 41.80, 44.90, sectionId = 1),
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
    private fun point(time: Long, state: CanonicalActivityState, section: Int = 0) = CanonicalPoint(
        timestampMs = time, lat = 41.7 + time * 1e-9, lon = 44.8,
        altitudeM = 700.0, sectionId = section, activityState = state,
    )

    @Test fun `riding export removes only motorized samples and keeps gaps timestamps and elevation`() {
        val source = listOf(
            point(1_000, CanonicalActivityState.LIKELY_MOTORIZED),
            point(2_000, CanonicalActivityState.DOWNHILL),
            point(3_000, CanonicalActivityState.STILL),
            point(4_000, CanonicalActivityState.LIKELY_MOTORIZED),
            point(5_000, CanonicalActivityState.LIKELY_MOTORIZED),
            point(6_000, CanonicalActivityState.TRANSIT),
            point(7_000, CanonicalActivityState.UNKNOWN),
            point(8_000, CanonicalActivityState.DOWNHILL, section = 1),
            point(20_000, CanonicalActivityState.DOWNHILL, section = 1),
            point(21_000, CanonicalActivityState.LIKELY_MOTORIZED, section = 1),
        )
        val original = source.toList()
        val points = TrackExport.processedPoints(source, excludeTransport = true)
        assertEquals(listOf(2_000L, 3_000L, 6_000L, 7_000L, 8_000L, 20_000L), points.map { it.timestampMs })
        assertEquals(listOf(0, 0, 1, 1, 2, 3), points.map { it.sectionId })
        assertTrue(points.all { it.altitudeM == 700.0 })
        assertEquals(original, source)
        val file = Files.createTempFile("nakvali-riding", ".gpx").toFile()
        try {
            GpxExporter.write(points, "Riding", file)
            val document = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                .newDocumentBuilder().parse(file)
            assertEquals(4, document.getElementsByTagName("trkseg").length)
            assertEquals(6, document.getElementsByTagName("trkpt").length)
            assertEquals("1970-01-01T00:00:06Z", document.getElementsByTagName("time").item(2).textContent)
        } finally { file.delete() }
    }

    @Test fun `whole track export retains transport while empty and transport only riding exports stay empty`() {
        val transport = listOf(point(1_000, CanonicalActivityState.LIKELY_MOTORIZED))
        assertEquals(1, TrackExport.processedPoints(transport).size)
        assertTrue(TrackExport.processedPoints(transport, excludeTransport = true).isEmpty())
        assertTrue(TrackExport.processedPoints(emptyList(), excludeTransport = true).isEmpty())
    }
}
