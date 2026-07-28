package com.dhava.core.recording

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins the crash-recovery repair routine against the real failure mode
 * (2026-07 OnePlus "o-kill"): a gzip stream truncated mid-flight by a hard
 * process kill. Every complete line before the cut must survive, the tail
 * partial line must be dropped, and the rewritten file must be a valid,
 * finished gzip.
 */
class RecordingRecoveryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val metaLine =
        """{"type":"meta","version":1,"activity_id":"abc","device":"OnePlus 9 Pro",""" +
            """"os":"android-16","app_version":"0.1.0","started_at_ms":1770000000000}"""

    private fun sampleLines(count: Int): List<String> =
        (0 until count).map { i ->
            """{"type":"gps","timestamp_ms":${1770000000000 + i * 1000},"lat":46.5,"lon":7.5}"""
        }

    private fun gzip(lines: List<String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        GZIPOutputStream(bytes).use { gz ->
            lines.forEach { line ->
                gz.write(line.toByteArray())
                gz.write('\n'.code)
            }
        }
        return bytes.toByteArray()
    }

    /** Decodes fully — throws if the stream is not a valid finished gzip. */
    private fun gunzipLines(bytes: ByteArray): List<String> =
        GZIPInputStream(ByteArrayInputStream(bytes)).readBytes()
            .toString(Charsets.UTF_8)
            .split('\n')
            .filter { it.isNotEmpty() }

    private fun repair(input: ByteArray): Pair<RecordingRecovery.RepairStats, ByteArray> {
        val out = ByteArrayOutputStream()
        val stats = RecordingRecovery.repair(ByteArrayInputStream(input), out)
        return stats to out.toByteArray()
    }

    @Test
    fun `intact file recovers every line with correct stats`() {
        val lines = listOf(metaLine) + sampleLines(100)
        val (stats, rewritten) = repair(gzip(lines))

        assertEquals(lines, gunzipLines(rewritten))
        assertEquals(101, stats.lineCount)
        assertEquals(1770000000000L, stats.metaStartedAtMs)
        assertEquals(1770000000000L, stats.firstTimestampMs)
        assertEquals(1770000099000L, stats.lastTimestampMs)
        assertEquals(1770000000000L, stats.startedAtMs)
        assertEquals(1770000099000L, stats.endedAtMs)
    }

    @Test
    fun `out of order writer rows use timestamp extrema for recovery boundary`() {
        val lines = listOf(
            metaLine,
            """{"type":"gps","timestamp_ms":1770000003000,"lat":46.5,"lon":7.5}""",
            """{"type":"baro","timestamp_ms":1770000003050,"pressure_hpa":934.2}""",
            // Stationary pre-roll is intentionally written after newer
            // critical rows even though its sensor timestamp is older.
            """{"type":"imu","timestamp_ms":1770000001000,"accel":[0.0,0.0,9.81],""" +
                """"gyro":[0.0,0.0,0.0]}""",
        )

        val (stats, rewritten) = repair(gzip(lines))

        assertEquals(lines, gunzipLines(rewritten))
        assertEquals(1770000001000L, stats.firstTimestampMs)
        assertEquals(1770000003050L, stats.lastTimestampMs)
        assertEquals(
            "A resumed ride must pause after every already-persisted sample",
            1770000003050L,
            stats.endedAtMs,
        )
    }

    @Test
    fun `truncated gzip recovers all complete lines and rewrites a valid gzip`() {
        val lines = listOf(metaLine) + sampleLines(1000)
        val full = gzip(lines)
        // Chop off the last third of the COMPRESSED bytes — the incident's
        // exact shape (kill mid-write: no final deflate block, no trailer).
        val truncated = full.copyOf(full.size * 2 / 3)

        val (stats, rewritten) = repair(truncated)
        val recovered = gunzipLines(rewritten) // must decode without error

        assertTrue("some lines must survive", stats.lineCount > 0)
        assertTrue("truncation must lose the tail", stats.lineCount < lines.size)
        assertEquals(stats.lineCount, recovered.size)
        // Recovered lines are exactly a prefix of the original — nothing
        // reordered, nothing corrupted, partial tail line discarded.
        assertEquals(lines.take(recovered.size), recovered)
        assertEquals(1770000000000L, stats.metaStartedAtMs)
        // ended_at derives from the last recovered sample (meta is line 0,
        // sample i carries 1770000000000 + i*1000).
        assertEquals(1770000000000L + (stats.lineCount - 2) * 1000L, stats.endedAtMs)
    }

    @Test
    fun `multi member gzip from a resumed recording recovers all members`() {
        // A resumed recording appends a second gzip member (RFC 1952);
        // repair must read across the member boundary.
        val first = listOf(metaLine) + sampleLines(10)
        val second = sampleLines(5).map { it.replace("46.5", "46.6") }
        val (stats, rewritten) = repair(gzip(first) + gzip(second))

        assertEquals(16, stats.lineCount)
        assertEquals(first + second, gunzipLines(rewritten))
    }

    @Test
    fun `garbage input recovers nothing`() {
        val (stats, rewritten) = repair(byteArrayOf(1, 2, 3, 4, 5))
        assertEquals(0, stats.lineCount)
        assertNull(stats.startedAtMs)
        assertNull(stats.endedAtMs)
        // Even then the output is a valid (empty) gzip stream.
        assertEquals(emptyList<String>(), gunzipLines(rewritten))
    }

    @Test
    fun `repairFile rewrites a truncated file in place`() {
        val lines = listOf(metaLine) + sampleLines(500)
        val full = gzip(lines)
        val file = tmp.newFile("abc.jsonl.gz")
        file.writeBytes(full.copyOf(full.size / 2))

        val stats = RecordingRecovery.repairFile(file)!!

        // The file on disk is now a valid finished gzip of the survivors.
        val recovered = gunzipLines(file.readBytes())
        assertEquals(stats.lineCount, recovered.size)
        assertEquals(lines.take(recovered.size), recovered)
        assertEquals(1770000000000L, stats.startedAtMs)
        // No leftover temp file.
        assertEquals(listOf(file.name), file.parentFile!!.list()!!.toList())
    }

    @Test
    fun `repairFile keeps an unrecoverable file untouched and returns null`() {
        val file = tmp.newFile("junk.jsonl.gz")
        val junk = byteArrayOf(9, 9, 9)
        file.writeBytes(junk)

        assertNull(RecordingRecovery.repairFile(file))
        // Raw data is never deleted: the damaged original stays as-is.
        assertTrue(file.exists())
        assertEquals(junk.toList(), file.readBytes().toList())
    }

    @Test
    fun `repairFile returns null for a missing file`() {
        assertNull(RecordingRecovery.repairFile(tmp.root.resolve("nope.jsonl.gz")))
    }
}
