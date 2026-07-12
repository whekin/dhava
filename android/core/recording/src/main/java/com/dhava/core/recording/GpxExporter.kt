package com.dhava.core.recording

import java.io.File
import java.time.Instant

/** Creates a standards-friendly GPX 1.1 track from the recorded GPS fixes. */
object GpxExporter {
    fun write(points: List<RecordLine.Gps>, name: String, output: File): File {
        output.parentFile?.mkdirs()
        output.bufferedWriter().use { out ->
            out.appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            out.appendLine("<gpx version=\"1.1\" creator=\"Dhava\" xmlns=\"http://www.topografix.com/GPX/1/1\">")
            out.appendLine("  <trk><name>${escape(name)}</name><trkseg>")
            points.forEach { point ->
                out.append("    <trkpt lat=\"").append(point.lat.toString())
                    .append("\" lon=\"").append(point.lon.toString()).appendLine("\">")
                point.altitudeM?.let { out.appendLine("      <ele>$it</ele>") }
                out.appendLine("      <time>${Instant.ofEpochMilli(point.timestampMs)}</time>")
                out.appendLine("    </trkpt>")
            }
            out.appendLine("  </trkseg></trk>")
            out.appendLine("</gpx>")
        }
        return output
    }

    private fun escape(value: String): String = value
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
}
