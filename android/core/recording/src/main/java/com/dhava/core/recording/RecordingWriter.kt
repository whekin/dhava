package com.dhava.core.recording

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

/**
 * Streams [RecordLine]s into a gzip-compressed JSONL file.
 *
 * Sensor callbacks run on hot system threads and must never block, so
 * [write] only enqueues into an unbounded [Channel]; a dedicated
 * single-thread dispatcher drains it, serializes, and writes through a
 * buffered gzip stream. The stream is sync-flushed every ~2 s so a hard kill
 * loses at most a couple of seconds of data (this is what let the 2026-07
 * OnePlus "o-kill" incident file be recovered — see [RecordingRecovery]).
 *
 * [append] resumes an interrupted recording by appending a *new gzip member*
 * to the existing (already repaired) file. Concatenated gzip members are a
 * valid gzip stream per RFC 1952; both java's GZIPInputStream and `gzip -dc`
 * decode multi-member files transparently.
 */
internal class RecordingWriter(private val file: File, private val append: Boolean = false) {

    private companion object {
        const val FLUSH_INTERVAL_MS = 2_000L
    }

    private val channel = Channel<RecordLine>(Channel.UNLIMITED)
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
                for (line in channel) {
                    output.write(RecordLineJson.encodeToString(line).toByteArray())
                    output.write('\n'.code)
                    val now = System.currentTimeMillis()
                    if (now - lastFlushAt >= FLUSH_INTERVAL_MS) {
                        output.flush()
                        lastFlushAt = now
                    }
                }
            } finally {
                // Channel closed (or scope cancelled): finish the gzip stream.
                output.close()
            }
        }
    }

    /** Non-blocking enqueue; safe to call from sensor callbacks. */
    fun write(line: RecordLine) {
        channel.trySend(line)
    }

    /** Drains pending lines, closes the file, and releases the writer thread. */
    suspend fun close() {
        channel.close()
        writerJob.join()
        dispatcher.close()
    }
}
