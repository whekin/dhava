package com.nakvali.core.recording

import com.garmin.fit.*
import java.nio.file.Files
import java.util.zip.GZIPInputStream
import org.junit.Assert.*
import org.junit.Test

class FitExporterTest {
    private val start = 1_770_000_000_127L
    private val points = listOf(
        TrackExportPoint(start, 41.7, 44.8, 1400.12, runId = 0, odometerM = 0.0),
        TrackExportPoint(start + 200, 41.70001, 44.8, 1399.42, runId = 0, odometerM = 1.25),
        TrackExportPoint(start + 400, 41.70002, 44.8, 1398.12, runId = 0, odometerM = 2.75),
        TrackExportPoint(start + 2_500_000, 41.8, 44.8, 1400.0, sectionId = 1, runId = 1, odometerM = 2.75),
        TrackExportPoint(start + 2_500_200, 41.80001, 44.8, 1398.0, sectionId = 1, runId = 1, odometerM = 4.5),
    )

    @Test fun `official decoder reads records exact milliseconds laps and paused timer`() {
        val file = Files.createTempFile("nakvali", ".fit").toFile()
        try {
            FitExporter.write(points, "Shuttle day", file)
            assertTrue(file.inputStream().use { Decode().checkFileIntegrity(it) })
            val messages = mutableListOf<Mesg>()
            assertTrue(file.inputStream().use { Decode().read(it, MesgListener { mesg -> messages += Mesg(mesg) }) })
            assertEquals(MesgNum.FILE_ID, messages.first().num)
            assertEquals(com.garmin.fit.File.ACTIVITY, FileIdMesg(messages.first()).type)
            val records = messages.filter { it.num == MesgNum.RECORD }.map(::RecordMesg)
            assertEquals(points.size, records.size)
            records.zip(points).forEach { (record, point) ->
                val fraction = record.developerFields.single().value as Number
                assertEquals(point.timestampMs, record.timestamp.date.time + fraction.toLong())
                assertEquals((point.timestampMs % 1000) / 1000.0, record.time128.toDouble(), 1.0 / 128)
                assertEquals(point.lat, record.positionLat * (180.0 / 2147483648.0), 0.0000001)
                assertEquals(point.lon, record.positionLong * (180.0 / 2147483648.0), 0.0000001)
                assertEquals(point.odometerM, record.distance.toDouble(), 0.02)
                assertEquals(point.altitudeM!!, record.enhancedAltitude.toDouble(), 0.11)
            }
            val laps = messages.filter { it.num == MesgNum.LAP }.map(::LapMesg)
            assertEquals(2, laps.size)
            assertEquals(2.75, laps[0].totalDistance.toDouble(), 0.02)
            assertEquals(1.75, laps[1].totalDistance.toDouble(), 0.02)
            val session = SessionMesg(messages.single { it.num == MesgNum.SESSION })
            assertEquals(4.5, session.totalDistance.toDouble(), 0.02)
            assertEquals(0.6, session.totalTimerTime.toDouble(), 0.001)
            assertEquals(2500.2, session.totalElapsedTime.toDouble(), 0.001)
            assertEquals(Sport.CYCLING, session.sport)
            val events = messages.filter { it.num == MesgNum.EVENT }.map(::EventMesg)
            assertEquals(listOf(EventType.START, EventType.STOP_ALL, EventType.START, EventType.STOP_ALL), events.map { it.eventType })
            assertEquals(1, messages.count { it.num == MesgNum.ACTIVITY })
        } finally { file.delete() }
    }

    @Test fun `large 5 Hz FIT and FIT gzip stay compact and valid`() {
        val file = Files.createTempFile("nakvali-long", ".fit").toFile()
        val large = List(54_000) { index ->
            TrackExportPoint(start + index * 200L, 41.7 + index * 1e-7, 44.8 + index * 1e-7,
                1400.0 - index * .01, runId = index / 18_000, odometerM = index * .73)
        }
        val gz = java.io.File(file.parentFile, "${file.name}.gz")
        val tcx = java.io.File(file.parentFile, "${file.name}.tcx")
        val tcxGz = java.io.File(file.parentFile, "${file.name}.tcx.gz")
        try {
            FitExporter.write(large, "Three long runs", file)
            TrackFileExport.gzip(file, gz)
            TcxExporter.write(large, "Three long runs", tcx)
            TrackFileExport.gzip(tcx, tcxGz)
            println("54,000 identical points: TCX=${tcx.length()}; TCX.GZ=${tcxGz.length()}; FIT=${file.length()}; FIT.GZ=${gz.length()} bytes")
            assertTrue(file.length() < 2_000_000)
            assertTrue(gz.length() < file.length())
            assertTrue(file.inputStream().use { Decode().checkFileIntegrity(it) })
            assertArrayEquals(file.readBytes(), GZIPInputStream(gz.inputStream()).use { it.readBytes() })
        } finally { file.delete(); gz.delete(); tcx.delete(); tcxGz.delete() }
    }
    @Test fun `e bike sport and long Unicode title produce a valid file`() {
        val file = Files.createTempFile("nakvali-unicode", ".fit").toFile()
        try {
            FitExporter.write(points, "🚵".repeat(200), file, BikeType.EBIKE)
            assertTrue(file.inputStream().use { Decode().checkFileIntegrity(it) })
            var session: SessionMesg? = null
            file.inputStream().use { Decode().read(it, MesgListener { mesg ->
                if (mesg.num == MesgNum.SESSION) session = SessionMesg(mesg)
            }) }
            assertEquals(SubSport.E_BIKE_MOUNTAIN, session!!.subSport)
            assertEquals("🚵".repeat(60), session!!.sportProfileName)
        } finally { file.delete() }
    }

}
