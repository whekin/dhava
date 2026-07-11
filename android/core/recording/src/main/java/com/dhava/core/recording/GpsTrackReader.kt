package com.dhava.core.recording

import java.io.File
import java.io.IOException
import java.util.zip.GZIPInputStream

/**
 * ============================================================================
 * DISPLAY-ONLY GPS extraction — this is NOT (and must never become) a fusion
 * implementation.
 *
 * Architecture principle 2: ALL timing, gate, distance, speed, descent and
 * airtime logic lives in Rust (`fusion-core`), running on-device via UniFFI.
 * Live and canonical results must never diverge, so no analysis of the raw
 * data may be reimplemented in Kotlin.
 *
 * The only job of this reader is to pull the raw `gps` lines out of a
 * recording so the activity detail screen can draw a polyline on a map and
 * show rough placeholder numbers until the fusion-core wiring lands. It feeds
 * the map and nothing else. Do not add filtering, smoothing, snapping,
 * timing or any other "small improvement" here — that belongs in fusion-core.
 * ============================================================================
 */
object GpsTrackReader {

    /**
     * Streams the raw `.jsonl.gz` once and returns every complete `gps` line
     * in file order. All other line types (`meta`, `imu`, `baro`) are skipped
     * with a cheap substring test before any JSON decoding — IMU runs at
     * ~500 Hz, so a full parse of every line would dominate the pass.
     *
     * Damage-tolerant by construction:
     *  - multi-member gzip (a crash-resumed recording appends a new member,
     *    see [RecordingWriter]) decodes transparently — [GZIPInputStream]
     *    reads concatenated members per RFC 1952;
     *  - a truncated file (process killed mid-flush) throws [IOException]
     *    mid-stream: every line decoded before that point is kept;
     *  - an individually corrupt line is skipped, the pass continues.
     *
     * Returns an empty list for a missing/unreadable file or a recording
     * without a single GPS fix.
     */
    fun read(file: File): List<RecordLine.Gps> {
        if (!file.isFile) return emptyList()
        val points = ArrayList<RecordLine.Gps>()
        try {
            GZIPInputStream(file.inputStream()).bufferedReader(Charsets.UTF_8).use { reader ->
                for (line in reader.lineSequence()) {
                    if (GPS_MARKER !in line) continue
                    val decoded = runCatching {
                        RecordLineJson.decodeFromString<RecordLine>(line)
                    }.getOrNull()
                    if (decoded is RecordLine.Gps) points.add(decoded)
                }
            }
        } catch (_: IOException) {
            // Truncated or damaged tail: keep everything decoded so far.
        }
        return points
    }

    /** Fast pre-filter; the JSON decode below still verifies the type. */
    private const val GPS_MARKER = "\"type\":\"gps\""
}
