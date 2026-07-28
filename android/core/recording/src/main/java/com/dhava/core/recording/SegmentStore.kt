package com.dhava.core.recording

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Files for authored segments and for their derived results.
 *
 * `segments/<id>.json` is rider-authored durable input: it is only written by
 * an explicit create/rename/delete. `segment-results/<id>.results.json.gz` and
 * the shared track-bounds cache are derived and may be deleted at any time —
 * the next match recomputes them from canonical artifacts.
 */
internal class SegmentStore(
    private val segmentsDir: File,
    private val resultsDir: File,
) {
    private val mutex = Mutex()

    suspend fun loadSegments(): List<StoredSegment> = mutex.withLock {
        segmentsDir.listFiles { file -> file.name.endsWith(SEGMENT_SUFFIX) }
            ?.mapNotNull { file ->
                runCatching { SegmentJson.decodeFromString<StoredSegment>(file.readText()) }
                    .getOrNull()
            }
            ?.sortedBy { it.createdAtMs }
            .orEmpty()
    }

    suspend fun saveSegment(segment: StoredSegment) = mutex.withLock {
        segmentsDir.mkdirs()
        writeAtomically(segmentFile(segment.id), SegmentJson.encodeToString(segment).toByteArray())
    }

    /** Removes an authored segment together with every result derived from it. */
    suspend fun deleteSegment(id: String) = mutex.withLock {
        segmentFile(id).delete()
        resultsFile(id).delete()
        temporaryFile(resultsFile(id)).delete()
    }

    suspend fun loadResults(segmentId: String): SegmentResults? = mutex.withLock {
        val file = resultsFile(segmentId)
        if (!file.isFile) return@withLock null
        runCatching {
            GZIPInputStream(file.inputStream()).bufferedReader().use { reader ->
                SegmentJson.decodeFromString<SegmentResults>(reader.readText())
            }
        }.getOrNull()
    }

    suspend fun saveResults(segmentId: String, results: SegmentResults) = mutex.withLock {
        resultsDir.mkdirs()
        val bytes = java.io.ByteArrayOutputStream().also { buffer ->
            GZIPOutputStream(buffer).bufferedWriter().use { writer ->
                writer.write(SegmentJson.encodeToString(results))
            }
        }.toByteArray()
        writeAtomically(resultsFile(segmentId), bytes)
    }

    suspend fun loadTrackBounds(): Map<String, TrackBoundsEntry> = mutex.withLock {
        val file = boundsFile()
        if (!file.isFile) return@withLock emptyMap()
        runCatching {
            SegmentJson.decodeFromString<List<TrackBoundsEntry>>(file.readText())
                .associateBy { it.recordingId }
        }.getOrElse { emptyMap() }
    }

    suspend fun saveTrackBounds(entries: Map<String, TrackBoundsEntry>) = mutex.withLock {
        resultsDir.mkdirs()
        writeAtomically(
            boundsFile(),
            SegmentJson.encodeToString(entries.values.toList()).toByteArray(),
        )
    }

    /** Drops every derived result; authored segments are never touched. */
    suspend fun clearResults(): Int = mutex.withLock {
        val removed = resultsDir.listFiles { file -> file.name.endsWith(RESULTS_SUFFIX) }
            ?.count { it.delete() }
            ?: 0
        boundsFile().delete()
        removed
    }

    private fun segmentFile(id: String): File = File(segmentsDir, "$id$SEGMENT_SUFFIX")

    private fun resultsFile(segmentId: String): File =
        File(resultsDir, "$segmentId$RESULTS_SUFFIX")

    private fun boundsFile(): File = File(resultsDir, BOUNDS_FILE)

    private fun temporaryFile(target: File): File = File(target.parentFile, "${target.name}.tmp")

    private fun writeAtomically(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val temporary = temporaryFile(target)
        temporary.writeBytes(bytes)
        try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    internal companion object {
        // v1: draft gate segments, per-recording attempts and rejections.
        const val RESULTS_SCHEMA_VERSION = 1
        private const val SEGMENT_SUFFIX = ".segment.json"
        private const val RESULTS_SUFFIX = ".results.json.gz"
        private const val BOUNDS_FILE = "track-bounds.json"
        val SegmentJson = Json {
            encodeDefaults = true
            explicitNulls = false
            ignoreUnknownKeys = true
        }
    }
}
