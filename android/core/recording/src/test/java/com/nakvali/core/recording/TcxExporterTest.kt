package com.nakvali.core.recording

import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class TcxExporterTest {
    private fun write(points: List<TrackExportPoint>, name: String = "Shuttle day"): Element {
        val file = Files.createTempFile("nakvali", ".tcx").toFile()
        try {
            TcxExporter.write(points, name, file)
            return DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(file).documentElement
        } finally {
            file.delete()
        }
    }

    private fun texts(root: Element, tag: String): List<String> {
        val nodes = root.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it).textContent }
    }

    /**
     * The whole point of TCX over GPX: the removed shuttle between the two runs
     * is 5 km wide on the map and must cost zero, because the file states the
     * distance instead of letting the reader measure the straight line.
     */
    @Test fun `each run is a lap and the odometer never crosses the shuttle`() {
        val points = listOf(
            TrackExportPoint(1_000, 41.70, 44.80, altitudeM = 1400.0, sectionId = 0, runId = 0, odometerM = 0.0),
            TrackExportPoint(61_000, 41.69, 44.80, altitudeM = 1100.0, sectionId = 0, runId = 0, odometerM = 1_200.5),
            // Forty minutes of uplift were dropped; the odometer stands still.
            TrackExportPoint(2_500_000, 41.70, 44.80, altitudeM = 1400.0, sectionId = 1, runId = 1, odometerM = 1_200.5),
            TrackExportPoint(2_560_000, 41.68, 44.80, altitudeM = 1000.0, sectionId = 1, runId = 1, odometerM = 3_400.25),
        )

        val root = write(points)

        assertEquals(2, root.getElementsByTagName("Lap").length)
        assertEquals(4, root.getElementsByTagName("Trackpoint").length)
        // Per lap in document order: the lap's own distance, then its points'
        // odometer readings. The shuttle between them adds nothing.
        assertEquals(
            listOf("1200.50", "0.00", "1200.50", "2199.75", "1200.50", "3400.25"),
            texts(root, "DistanceMeters"),
        )
        assertEquals(listOf("60.00", "60.00"), texts(root, "TotalTimeSeconds"))
        assertEquals(
            listOf("1970-01-01T00:00:01Z", "1970-01-01T00:41:40Z"),
            (0 until 2).map { (root.getElementsByTagName("Lap").item(it) as Element).getAttribute("StartTime") },
        )
    }

    @Test fun `writes a biking activity with positions altitude and an escaped name`() {
        val root = write(
            listOf(TrackExportPoint(1_770_000_001_000, 41.7, 44.8, altitudeM = 712.4, odometerM = 10.0)),
            name = "Ride & trail",
        )

        assertEquals("Biking", (root.getElementsByTagName("Activity").item(0) as Element).getAttribute("Sport"))
        assertEquals(listOf("2026-02-02T02:40:01Z"), texts(root, "Id"))
        assertEquals(listOf("41.7"), texts(root, "LatitudeDegrees"))
        assertEquals(listOf("44.8"), texts(root, "LongitudeDegrees"))
        assertEquals(listOf("712.4"), texts(root, "AltitudeMeters"))
        assertEquals(listOf("Ride & trail"), texts(root, "Notes"))
        assertTrue(texts(root, "Intensity").all { it == "Active" })
    }
    @Test fun `three hours at 5 Hz fit below 20 MB without dropping samples`() {
        val file = Files.createTempFile("nakvali-long-ride", ".tcx").toFile()
        val points = List(54_000) { index ->
            TrackExportPoint(
                timestampMs = 1_770_000_000_000 + index * 200L,
                lat = 41.700123456789 + index * 0.0000001,
                lon = 44.800987654321 + index * 0.0000001,
                altitudeM = 1400.123456789 - index * 0.01,
                runId = index / 18_000, odometerM = index * 0.73,
            )
        }
        try {
            TcxExporter.write(points, "Three long runs", file)
            println("54,000-point TCX size: ${file.length()} bytes")
            assertTrue("TCX is ${file.length()} bytes", file.length() < 20_000_000)
            var count = 0
            var laps = 0
            val parser = javax.xml.parsers.SAXParserFactory.newInstance().newSAXParser()
            parser.parse(file, object : org.xml.sax.helpers.DefaultHandler() {
                override fun startElement(uri: String?, localName: String?, qName: String?, attributes: org.xml.sax.Attributes?) {
                    if (qName == "Trackpoint") count++
                    if (qName == "Lap") laps++
                }
            })
            assertEquals(points.size, count)
            assertEquals(3, laps)
        } finally { file.delete() }
    }

    @Test fun `compact XML preserves whitespace inside rider supplied names`() {
        val root = write(listOf(TrackExportPoint(1_000, 41.7, 44.8)), "  Ride & trail\n  second line  ")
        assertEquals(listOf("  Ride & trail\n  second line  "), texts(root, "Notes"))
    }

}
