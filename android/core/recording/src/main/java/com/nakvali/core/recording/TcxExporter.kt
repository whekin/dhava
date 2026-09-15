package com.nakvali.core.recording

import java.io.File
import java.time.Instant
import java.util.Locale

/**
 * Creates a Garmin TCX v2 activity.
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
            out.appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            out.appendLine("<TrainingCenterDatabase xmlns=\"$SCHEMA\"")
            out.appendLine("    xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"")
            out.appendLine("    xsi:schemaLocation=\"$SCHEMA http://www.garmin.com/xmlschemas/TrainingCenterDatabasev2.xsd\">")
            out.appendLine("  <Activities>")
            out.appendLine("    <Activity Sport=\"Biking\">")
            out.appendLine("      <Id>${time(points.firstOrNull()?.timestampMs ?: 0)}</Id>")
            points.groupBy { it.runId }.values.forEach { lap ->
                val first = lap.first()
                val last = lap.last()
                out.appendLine("      <Lap StartTime=\"${time(first.timestampMs)}\">")
                out.appendLine("        <TotalTimeSeconds>${
                    decimal((last.timestampMs - first.timestampMs) / 1_000.0)
                }</TotalTimeSeconds>")
                out.appendLine("        <DistanceMeters>${
                    decimal(last.odometerM - first.odometerM)
                }</DistanceMeters>")
                // Required by the schema and unknown to us; Strava ignores it.
                out.appendLine("        <Calories>0</Calories>")
                out.appendLine("        <Intensity>Active</Intensity>")
                out.appendLine("        <TriggerMethod>Manual</TriggerMethod>")
                out.appendLine("        <Track>")
                lap.forEach { point ->
                    out.appendLine("          <Trackpoint>")
                    out.appendLine("            <Time>${time(point.timestampMs)}</Time>")
                    out.appendLine("            <Position>")
                    out.appendLine("              <LatitudeDegrees>${point.lat}</LatitudeDegrees>")
                    out.appendLine("              <LongitudeDegrees>${point.lon}</LongitudeDegrees>")
                    out.appendLine("            </Position>")
                    point.altitudeM?.let { out.appendLine("            <AltitudeMeters>$it</AltitudeMeters>") }
                    out.appendLine("            <DistanceMeters>${decimal(point.odometerM)}</DistanceMeters>")
                    out.appendLine("          </Trackpoint>")
                }
                out.appendLine("        </Track>")
                out.appendLine("      </Lap>")
            }
            out.appendLine("      <Notes>${TrackExport.escape(name)}</Notes>")
            out.appendLine("      <Creator xsi:type=\"Device_t\">")
            out.appendLine("        <Name>Nakvali</Name>")
            out.appendLine("        <UnitId>0</UnitId>")
            out.appendLine("        <ProductID>0</ProductID>")
            out.appendLine("      </Creator>")
            out.appendLine("    </Activity>")
            out.appendLine("  </Activities>")
            out.appendLine("</TrainingCenterDatabase>")
        }
        return output
    }

    private fun time(timestampMs: Long): String = Instant.ofEpochMilli(timestampMs).toString()

    private fun decimal(value: Double): String = String.format(Locale.US, "%.2f", value)
}
