package com.nakvali.core.recording

import java.io.File
import java.util.zip.GZIPOutputStream

/** File representation only. Sampling never changes canonical fusion or totals. */
enum class TrackFileFormat(val extension: String, val mimeType: String) {
    FIT("fit", "application/vnd.ant.fit"),
    TCX("tcx", "application/vnd.garmin.tcx+xml"),
    GPX("gpx", "application/gpx+xml"),
}

enum class TrackExportRate(val hz: Int) { ONE_HZ(1), FIVE_HZ(5) }

object TrackFileExport {
    /**
     * Select existing points; do not interpolate or calculate new positions.
     * Preserve both sides of every Rust-authored run/section boundary, including
     * sub-second runs. Keeping lap endpoints also preserves the Rust odometer.
     */
    fun sample(points: List<TrackExportPoint>, rate: TrackExportRate): List<TrackExportPoint> {
        if (rate == TrackExportRate.FIVE_HZ || points.size < 3) return points
        val kept = ArrayList<TrackExportPoint>()
        var lastKeptMs = Long.MIN_VALUE
        points.forEachIndexed { index, point ->
            val before = points.getOrNull(index - 1)
            val after = points.getOrNull(index + 1)
            val boundary = before == null || after == null ||
                before.runId != point.runId || after.runId != point.runId ||
                before.sectionId != point.sectionId || after.sectionId != point.sectionId
            if (boundary || point.timestampMs - lastKeptMs >= 1_000) {
                kept += point
                lastKeptMs = point.timestampMs
            }
        }
        return kept
    }

    fun write(points: List<TrackExportPoint>, name: String, output: File, format: TrackFileFormat, bikeType: BikeType? = null): File =
        when (format) {
            TrackFileFormat.FIT -> FitExporter.write(points, name, output, bikeType)
            TrackFileFormat.TCX -> TcxExporter.write(points, name, output)
            TrackFileFormat.GPX -> GpxExporter.write(points, name, output)
        }

    /** Stream compression; source and output are different cache files. */
    fun gzip(source: File, output: File = File(source.parentFile, "${source.name}.gz")): File {
        require(source.canonicalFile != output.canonicalFile)
        output.parentFile?.mkdirs()
        try {
            source.inputStream().buffered().use { input ->
                GZIPOutputStream(output.outputStream().buffered()).use { input.copyTo(it) }
            }
        } catch (error: Exception) { output.delete(); throw error }
        return output
    }
}
