package com.nakvali.core.recording

import java.io.File
import java.time.Instant

/**
 * Creates a standards-friendly GPX 1.1 track without bridging pauses.
 *
 * GPX has no distance field at all, so every reader derives kilometres from the
 * coordinates and charges the rider for the straight line across each removed
 * shuttle. Use [TcxExporter] where the distance has to be right.
 */
object GpxExporter {
    fun write(points: List<TrackExportPoint>, name: String, output: File): File {
        output.parentFile?.mkdirs()
        output.bufferedWriter().use { out ->
            out.appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            out.appendLine("<gpx version=\"1.1\" creator=\"Nakvali\" xmlns=\"http://www.topografix.com/GPX/1/1\">")
            out.appendLine("  <trk>")
            out.appendLine("    <name>${TrackExport.escape(name)}</name>")
            var openSectionId: Int? = null
            points.forEach { point ->
                if (point.sectionId != openSectionId) {
                    if (openSectionId != null) out.appendLine("    </trkseg>")
                    out.appendLine("    <trkseg>")
                    openSectionId = point.sectionId
                }
                out.append("      <trkpt lat=\"").append(point.lat.toString())
                    .append("\" lon=\"").append(point.lon.toString()).appendLine("\">")
                point.altitudeM?.let { out.appendLine("        <ele>$it</ele>") }
                out.appendLine("        <time>${Instant.ofEpochMilli(point.timestampMs)}</time>")
                out.appendLine("      </trkpt>")
            }
            if (openSectionId != null) out.appendLine("    </trkseg>")
            out.appendLine("  </trk>")
            out.appendLine("</gpx>")
        }
        return output
    }
}
