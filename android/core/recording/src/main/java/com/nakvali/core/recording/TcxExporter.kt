package com.nakvali.core.recording

import java.io.File
import java.time.Instant
import java.util.Locale

/**
 * Creates a compact Garmin TCX v2 activity, preserving every exported sample.
 * Whitespace between elements has no meaning; omitting it avoids spending
 * megabytes of the receiver’s file limit on pretty-print indentation.
 *
 * The reason to prefer it over GPX is `DistanceMeters`: TCX states the distance
 * instead of leaving readers to derive it from coordinates, so an excluded
 * shuttle costs nothing. The odometer comes from `rideOdometerM` rather than
 * from any arithmetic here, which is what keeps the uploaded figure equal to
 * the one the app shows.
 *
 * Each riding run becomes a `<Lap>`, so a shuttle day arrives as one lap per
 * descent instead of a single undifferentiated ride.
 */
object TcxExporter {
    private const val SCHEMA = "http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2"

    fun write(points: List<TrackExportPoint>, name: String, output: File): File {
        output.parentFile?.mkdirs()
        output.bufferedWriter().use { out ->
            out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            out.append("<TrainingCenterDatabase xmlns=\"$SCHEMA\"")
            out.append("    xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"")
            out.append("    xsi:schemaLocation=\"$SCHEMA http://www.garmin.com/xmlschemas/TrainingCenterDatabasev2.xsd\">")
            out.append("<Activities>")
            out.append("<Activity Sport=\"Biking\">")
            out.append("<Id>${time(points.firstOrNull()?.timestampMs ?: 0)}</Id>")
            points.groupBy { it.runId }.values.forEach { lap ->
                val first = lap.first()
                val last = lap.last()
                out.append("<Lap StartTime=\"${time(first.timestampMs)}\">")
                out.append("<TotalTimeSeconds>${
                    decimal((last.timestampMs - first.timestampMs) / 1_000.0)
                }</TotalTimeSeconds>")
                out.append("<DistanceMeters>${
                    decimal(last.odometerM - first.odometerM)
                }</DistanceMeters>")
                // Required by the TCX schema; no calorie measurement is available.
                out.append("<Calories>0</Calories>")
                out.append("<Intensity>Active</Intensity>")
                out.append("<TriggerMethod>Manual</TriggerMethod>")
                out.append("<Track>")
                lap.forEach { point ->
                    out.append("<Trackpoint>")
                    out.append("<Time>${time(point.timestampMs)}</Time>")
                    out.append("<Position>")
                    out.append("<LatitudeDegrees>${point.lat}</LatitudeDegrees>")
                    out.append("<LongitudeDegrees>${point.lon}</LongitudeDegrees>")
                    out.append("</Position>")
                    point.altitudeM?.let { out.append("<AltitudeMeters>$it</AltitudeMeters>") }
                    out.append("<DistanceMeters>${decimal(point.odometerM)}</DistanceMeters>")
                    out.append("</Trackpoint>")
                }
                out.append("</Track>")
                out.append("</Lap>")
            }
            out.append("<Notes>${TrackExport.escape(name)}</Notes>")
            out.append("<Creator xsi:type=\"Device_t\">")
            out.append("<Name>Nakvali</Name>")
            out.append("<UnitId>0</UnitId>")
            out.append("<ProductID>0</ProductID>")
            out.append("</Creator>")
            out.append("</Activity>")
            out.append("</Activities>")
            out.append("</TrainingCenterDatabase>")
        }
        return output
    }

    private fun time(timestampMs: Long): String = Instant.ofEpochMilli(timestampMs).toString()

    private fun decimal(value: Double): String = String.format(Locale.US, "%.2f", value)
}
