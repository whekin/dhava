package com.dhava.core.recording

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.serialization.encodeToString

/**
 * Streams [RecordLine]s into a gzip-compressed JSONL file.
 *
 * Sensor callbacks run on hot system threads and must never block, so
 * [write] only enqueues; a dedicated single-thread dispatcher drains,
 * serializes, and writes through a buffered gzip stream. Critical GPS/meta/
 * event rows have their own lossless queue, while the high-rate IMU backlog
 * is bounded so temporary storage stalls cannot grow process memory without
 * limit. The stream is sync-flushed every ~2 s so a hard kill loses at most a
 * couple of seconds of data (this is what let the 2026-07 OnePlus "o-kill"
 * incident file be recovered — see [RecordingRecovery]).
 *
 * [append] resumes an interrupted recording by appending a *new gzip member*
 * to the existing (already repaired) file. Concatenated gzip members are a
 * valid gzip stream per RFC 1952; both java's GZIPInputStream and `gzip -dc`
 * decode multi-member files transparently.
 */
internal class RecordingWriter(
    private val file: File,
    private val append: Boolean = false,
    imuQueueCapacity: Int = MAX_PENDING_IMU_LINES,
) {

    private companion object {
        const val FLUSH_INTERVAL_MS = 2_000L
        const val MAX_PENDING_IMU_LINES = 4_096
    }

    // GPS, meta and lifecycle events are tiny/rare and must never be lost.
    // IMU is the only high-rate source, so cap its backlog to prevent a flash
    // stall from turning an unbounded channel into a long-ride memory spike.
    private val criticalChannel = Channel<RecordLine>(Channel.UNLIMITED)
    private val imuChannel = Channel<RecordLine>(imuQueueCapacity)
    private val droppedImuSinceDiagnostic = AtomicInteger()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "recording-writer")
    }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val writerJob: Job

    init {
        file.parentFile?.mkdirs()
        writerJob = CoroutineScope(dispatcher).launch {
            // syncFlush=true: flush() emits a complete deflate block, so
            // everything written up to the last flush is decompressable even
            // if the process is killed before close() — without it the
            // deflater may sit on an arbitrary amount of buffered input.
            val output = GZIPOutputStream(BufferedOutputStream(FileOutputStream(file, append)), true)
            var lastFlushAt = System.currentTimeMillis()
            try {
                var criticalOpen = true
                var imuOpen = true
                while (criticalOpen || imuOpen) {
                    // Give position/lifecycle records priority over sensor
                    // backlog. Global line ordering is explicitly best-effort;
                    // every row carries its own monotonic timestamp.
                    val immediate = criticalChannel.tryReceive()
                    if (immediate.isSuccess) {
                        writeLine(output, immediate.getOrThrow())
                        continue
                    }
                    if (immediate.isClosed) criticalOpen = false

                    val selection = select<QueueSelection> {
                        if (criticalOpen) {
                            criticalChannel.onReceiveCatching {
                                QueueSelection.Critical(it.getOrNull())
                            }
                        }
                        if (imuOpen) {
                            imuChannel.onReceiveCatching {
                                QueueSelection.Imu(it.getOrNull())
                            }
                        }
                    }
                    val line = when (selection) {
                        is QueueSelection.Critical -> {
                            if (selection.line == null) criticalOpen = false
                            selection.line
                        }
                        is QueueSelection.Imu -> {
                            if (selection.line == null) imuOpen = false
                            selection.line
                        }
                    } ?: continue
                    writeLine(output, line)

                    val now = System.currentTimeMillis()
                    if (now - lastFlushAt >= FLUSH_INTERVAL_MS) {
                        output.flush()
                        lastFlushAt = now
                    }
                }
            } finally {
                // Channels closed (or scope cancelled): finish gzip stream.
                output.close()
            }
        }
    }

    private fun writeLine(output: GZIPOutputStream, line: RecordLine) {
        output.write(RecordLineJson.encodeToString(line).toByteArray())
        output.write('\n'.code)
    }

    /** Non-blocking enqueue; safe to call from sensor callbacks. */
    fun write(line: RecordLine) {
        if (line is RecordLine.Imu) {
            if (imuChannel.trySend(line).isFailure) {
                droppedImuSinceDiagnostic.incrementAndGet()
            }
        } else {
            criticalChannel.trySend(line)
        }
    }

    /**
     * Persists any queue-overflow count without affecting known pause/resume
     * semantics. Normally emits nothing; called by the service once a minute.
     */
    fun flushDiagnostics(timestampMs: Long) {
        val dropped = droppedImuSinceDiagnostic.getAndSet(0)
        if (dropped > 0) {
            criticalChannel.trySend(
                RecordLine.Event(timestampMs, "imu_overflow:$dropped"),
            )
        }
    }

    /** Drains pending lines, closes the file, and releases the writer thread. */
    suspend fun close() {
        flushDiagnostics(System.currentTimeMillis())
        criticalChannel.close()
        imuChannel.close()
        writerJob.join()
        dispatcher.close()
    }
}

private sealed interface QueueSelection {
    data class Critical(val line: RecordLine?) : QueueSelection
    data class Imu(val line: RecordLine?) : QueueSelection
}
