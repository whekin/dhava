package com.nakvali.core.recording

import java.io.File
import java.time.Instant

/** One exported GPX sample. A section change starts a new `<trkseg>`. */
data class GpxTrackPoint(
    val timestampMs: Long,
    val lat: Double,
    val lon: Double,
    val altitudeM: Double? = null,
    val sectionId: Int = 0,
)

/** Creates a standards-friendly GPX 1.1 track without bridging pauses. */
object GpxExporter {
    fun write(points: List<GpxTrackPoint>, name: String, output: File): File {
        output.parentFile?.mkdirs()
        output.bufferedWriter().use { out ->
            out.appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            out.appendLine("<gpx version=\"1.1\" creator=\"Nakvali\" xmlns=\"http://www.topografix.com/GPX/1/1\">")
            out.appendLine("  <trk>")
            out.appendLine("    <name>${escape(name)}</name>")
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

    private fun escape(value: String): String = value
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
}
