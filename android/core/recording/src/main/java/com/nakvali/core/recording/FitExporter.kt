package com.nakvali.core.recording

import com.garmin.fit.ActivityMesg
import com.garmin.fit.DateTime
import com.garmin.fit.DeveloperDataIdMesg
import com.garmin.fit.DeveloperField
import com.garmin.fit.DeviceInfoMesg
import com.garmin.fit.Event
import com.garmin.fit.EventMesg
import com.garmin.fit.EventType
import com.garmin.fit.FieldDescriptionMesg
import com.garmin.fit.FileEncoder
import com.garmin.fit.FileIdMesg
import com.garmin.fit.Fit
import com.garmin.fit.LapMesg
import com.garmin.fit.Manufacturer
import com.garmin.fit.Mesg
import com.garmin.fit.RecordMesg
import com.garmin.fit.SessionMesg
import com.garmin.fit.Sport
import com.garmin.fit.SubSport
import java.io.File
import java.util.Date
import kotlin.math.roundToInt

/**
 * FIT is a codec for the Rust-derived export points, not another fusion pass.
 * Every existing run is a lap, with timer events across removed shuttles/pauses.
 * Standard time128 retains sub-second samples to ~1/128 s; the declared developer
 * field preserves the exact original millisecond fraction for aware readers.
 */
object FitExporter {
    private const val GARMIN_EPOCH_MS = 631065600000L
    // Stable 16-byte identity for Nakvali's FIT developer fields (not a rider ID).
    private val APPLICATION_ID = "NakvaliExportV01".toByteArray(Charsets.US_ASCII)

    fun write(points: List<TrackExportPoint>, name: String, output: File, bikeType: BikeType? = null): File {
        require(points.isNotEmpty()) { "No track to export" }
        require(points.all { it.timestampMs >= GARMIN_EPOCH_MS && it.lat.isFinite() && it.lon.isFinite() })
        require(points.zipWithNext().all { (a, b) -> b.timestampMs > a.timestampMs }) { "Track times must increase" }
        val subSportValue = if (bikeType == BikeType.EBIKE) SubSport.E_BIKE_MOUNTAIN else SubSport.MOUNTAIN
        val titlePoints = name.codePoints().limit(60).toArray()
        val fitName = buildString { titlePoints.forEach { appendCodePoint(it) } }
        output.parentFile?.mkdirs()
        val encoder = FileEncoder(output, Fit.ProtocolVersion.V2_0)
        try {
            val first = points.first()
            val last = points.last()
            encoder.write(FileIdMesg().apply {
                type = com.garmin.fit.File.ACTIVITY
                manufacturer = Manufacturer.DEVELOPMENT
                product = 1
                productName = "Nakvali"
                timeCreated = time(first.timestampMs)
            })
            encoder.write(DeviceInfoMesg().apply {
                timestamp = time(first.timestampMs)
                manufacturer = Manufacturer.DEVELOPMENT
                productName = "Nakvali"
                deviceIndex = 0
            })
            val developer = DeveloperDataIdMesg().apply {
                developerDataIndex = 0
                APPLICATION_ID.forEachIndexed { index, byte -> setApplicationId(index, byte) }
            }
            val fraction = FieldDescriptionMesg().apply {
                developerDataIndex = 0
                fieldDefinitionNumber = 0
                fitBaseTypeId = Fit.BASE_TYPE_UINT16.toShort()
                setFieldName(0, "timestamp_fraction_ms")
                setUnits(0, "ms")
            }
            encoder.write(developer)
            encoder.write(fraction)
            fun precise(message: Mesg, timestampMs: Long): Mesg = message.apply {
                addDeveloperField(DeveloperField(fraction, developer).apply { setValue(timestampMs % 1000) })
            }
            fun timer(timestampMs: Long, type: EventType) {
                encoder.write(precise(EventMesg().apply {
                    timestamp = time(timestampMs)
                    event = Event.TIMER
                    eventType = type
                    eventGroup = 0
                }, timestampMs))
            }
            var timerSeconds = 0.0
            val laps = points.groupBy { it.runId }.values
            laps.forEachIndexed { index, lap ->
                val start = lap.first()
                val end = lap.last()
                val duration = (end.timestampMs - start.timestampMs) / 1000.0
                timerSeconds += duration
                timer(start.timestampMs, EventType.START)
                lap.forEach { point ->
                    encoder.write(precise(RecordMesg().apply {
                        timestamp = time(point.timestampMs)
                        time128 = (point.timestampMs % 1000) / 1000f
                        positionLat = semicircles(point.lat)
                        positionLong = semicircles(if (point.lon == 180.0) -180.0 else point.lon)
                        distance = point.odometerM.toFloat()
                        point.altitudeM?.takeIf(Double::isFinite)?.let { enhancedAltitude = it.toFloat() }
                    }, point.timestampMs))
                }
                timer(end.timestampMs, EventType.STOP_ALL)
                encoder.write(LapMesg().apply {
                    messageIndex = index
                    timestamp = time(end.timestampMs)
                    startTime = time(start.timestampMs)
                    totalElapsedTime = duration.toFloat()
                    totalTimerTime = duration.toFloat()
                    totalDistance = (end.odometerM - start.odometerM).toFloat()
                    sport = Sport.CYCLING
                    subSport = subSportValue
                    event = Event.LAP
                    eventType = EventType.STOP
                })
            }
            encoder.write(SessionMesg().apply {
                messageIndex = 0
                timestamp = time(last.timestampMs)
                startTime = time(first.timestampMs)
                totalElapsedTime = ((last.timestampMs - first.timestampMs) / 1000.0).toFloat()
                totalTimerTime = timerSeconds.toFloat()
                totalDistance = (last.odometerM - first.odometerM).toFloat()
                sport = Sport.CYCLING
                subSport = subSportValue
                sportProfileName = fitName
                firstLapIndex = 0
                numLaps = laps.size
                event = Event.SESSION
                eventType = EventType.STOP
            })
            encoder.write(ActivityMesg().apply {
                timestamp = time(last.timestampMs)
                totalTimerTime = timerSeconds.toFloat()
                numSessions = 1
                type = com.garmin.fit.Activity.MANUAL
                event = Event.ACTIVITY
                eventType = EventType.STOP
            })
        } finally { encoder.close() }
        return output
    }

    private fun time(milliseconds: Long) = DateTime(Date(milliseconds))
    private fun semicircles(degrees: Double): Int = (degrees * (2147483648.0 / 180.0)).roundToInt()
}
