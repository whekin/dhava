package com.dhava.core.recording

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class GpxTraceParserTest {
    @Test fun `parses the most detailed continuous section without bridging`() {
        val file = Files.createTempFile("dhava-import", ".gpx").toFile()
        file.writeText(
            """
            <gpx xmlns="http://www.topografix.com/GPX/1/1">
              <trk><trkseg>
                <trkpt lat="41.7000" lon="44.8000"/>
                <trkpt lat="41.7001" lon="44.8001"/>
              </trkseg><trkseg>
                <trkpt lat="41.7100" lon="44.8100"><ele>1000.5</ele></trkpt>
                <trkpt lat="41.7101" lon="44.8101"><ele>998.0</ele></trkpt>
                <trkpt lat="41.7102" lon="44.8102"/>
              </trkseg></trk>
            </gpx>
            """.trimIndent(),
        )

        val points = GpxTraceParser.parse(file)

        assertEquals(3, points.size)
        assertEquals(41.7100, points.first().lat, 0.0)
        assertEquals(1000.5, points.first().altitudeM)
        assertNull(points.last().altitudeM)
        assertEquals(listOf(0L, 1_000L, 2_000L), points.map { it.timestampMs })
    }

    @Test fun `rejects document type declarations`() {
        val file = Files.createTempFile("dhava-import-xxe", ".gpx").toFile()
        file.writeText(
            """<!DOCTYPE gpx [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <gpx><trk><trkseg><trkpt lat="1" lon="2"/><trkpt lat="2" lon="3"/></trkseg></trk></gpx>
            """.trimIndent(),
        )

        assertThrows(Exception::class.java) { GpxTraceParser.parse(file) }
    }
}
