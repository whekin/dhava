package com.dhava.core.recording

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Repair of raw recording files left behind by a hard process kill.
 *
 * Incident (2026-07, OnePlus 9 Pro / OxygenOS): the OEM power manager killed
 * the app mid-ride (ApplicationExitInfo reason=13 OTHER, "o-kill") while the
 * foreground service was recording. The writer flushes with SYNC_FLUSH every
 * ~2 s, so the `.jsonl.gz` on disk was a decompressable gzip stream missing
 * only its final deflate block and trailer — `gzip -dc` recovered 396k lines
 * (everything up to ~3 s before the kill). This routine does the same
 * programmatically: stream-decompress as many complete lines as possible,
 * then rewrite them as a fresh, properly finished gzip file.
 */
internal object RecordingRecovery {

    private const val NEWLINE = '\n'.code.toByte()

    /** What a repair pass managed to pull out of a damaged file. */
    data class RepairStats(
        val lineCount: Int,
        /** `started_at_ms` of the recovered meta line, if any. */
        val metaStartedAtMs: Long? = null,
        /** `timestamp_ms` of the first recovered sample line. */
        val firstTimestampMs: Long? = null,
        /** `timestamp_ms` of the last recovered sample line. */
        val lastTimestampMs: Long? = null,
    ) {
        /** Best-effort recording start; the meta line wins over the first sample. */
        val startedAtMs: Long? get() = metaStartedAtMs ?: firstTimestampMs

        /** Best-effort recording end: the last sample we still have. */
        val endedAtMs: Long? get() = lastTimestampMs ?: startedAtMs
    }

    /**
     * Streams every complete line of a possibly truncated gzip JSONL stream
     * into a fresh, finished gzip stream.
     *
     * A line counts only once its `\n` has been decompressed; a partial tail
     * line (the writer was killed mid-flush) is discarded. Truncation
     * surfaces as an [IOException] from [GZIPInputStream] — everything
     * decoded before it is kept. Multi-member gzip input (a recording that
     * was already resumed once, see [RecordingWriter]) decodes transparently:
     * [GZIPInputStream] reads concatenated members per RFC 1952.
     *
     * The caller owns both streams. [output] receives a complete gzip stream
     * (finished, not closed) before this returns.
     */
    fun repair(input: InputStream, output: OutputStream): RepairStats {
        var lineCount = 0
        var metaStartedAtMs: Long? = null
        var firstTimestampMs: Long? = null
        var lastTimestampMs: Long? = null

        val gzOut = GZIPOutputStream(BufferedOutputStream(output))
        // Lines are accumulated as raw bytes: a read() may split a multi-byte
        // UTF-8 character across buffers, so decoding happens per full line.
        val pending = ByteArrayOutputStream(256)
        try {
            val gzIn = GZIPInputStream(input)
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = gzIn.read(buf)
                if (n < 0) break
                var from = 0
                for (i in 0 until n) {
                    if (buf[i] != NEWLINE) continue
                    pending.write(buf, from, i - from)
                    from = i + 1
                    val lineBytes = pending.toByteArray()
                    pending.reset()
                    if (lineBytes.isEmpty()) continue

                    gzOut.write(lineBytes)
                    gzOut.write('\n'.code)
                    lineCount++

                    val line = String(lineBytes, Charsets.UTF_8)
                    val ts = extractLong(line, "timestamp_ms")
                    if (ts != null) {
                        if (firstTimestampMs == null) firstTimestampMs = ts
                        lastTimestampMs = ts
                    } else if (metaStartedAtMs == null) {
                        metaStartedAtMs = extractLong(line, "started_at_ms")
                    }
                }
                pending.write(buf, from, n - from)
            }
        } catch (_: IOException) {
            // Truncated (or otherwise damaged) input: keep every line decoded
            // so far; the bytes in `pending` are a partial line and are dropped.
        }
        gzOut.finish()
        gzOut.flush()
        return RepairStats(
            lineCount = lineCount,
            metaStartedAtMs = metaStartedAtMs,
            firstTimestampMs = firstTimestampMs,
            lastTimestampMs = lastTimestampMs,
        )
    }

    /**
     * Repairs a damaged recording file in place: recovered lines go to a temp
     * file that then atomically replaces the original (rename(2) over an
     * existing name is atomic on Linux/Android), so a crash mid-repair can
     * never eat the source data. Returns null when nothing was recoverable;
     * the original file is left untouched in that case.
     */
    fun repairFile(file: File): RepairStats? {
        if (!file.isFile) return null
        val tmp = File(file.parentFile, "${file.name}.tmp")
        val stats = try {
            file.inputStream().use { input ->
                tmp.outputStream().use { output -> repair(input, output) }
            }
        } catch (_: IOException) {
            tmp.delete()
            return null
        }
        if (stats.lineCount == 0 || !tmp.renameTo(file)) {
            tmp.delete()
            return null
        }
        return stats
    }

    /** Pulls `"key":<long>` out of a JSONL line without a full JSON parse. */
    private fun extractLong(line: String, key: String): Long? {
        val marker = "\"$key\":"
        val at = line.indexOf(marker)
        if (at < 0) return null
        var i = at + marker.length
        val start = i
        if (i < line.length && line[i] == '-') i++
        while (i < line.length && line[i].isDigit()) i++
        return if (i > start) line.substring(start, i).toLongOrNull() else null
    }
}
