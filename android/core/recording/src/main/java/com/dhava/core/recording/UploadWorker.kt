package com.dhava.core.recording

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Background upload of one saved recording via WorkManager.
 *
 * Save works fully offline: this worker is enqueued at save time with a
 * CONNECTED constraint, so with no network it just sits in the queue until
 * connectivity returns (surviving process death and reboots). Unique work per
 * recording (`upload-<id>`, KEEP) makes double-enqueueing harmless, and the
 * server id persisted after `create` keeps retries idempotent.
 */
class UploadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        private const val KEY_RECORDING_ID = "recording_id"

        /**
         * After this many failed attempts the entry is marked [RecordingStatus.FAILED]
         * and waits for a manual retry from the list instead of backing off forever.
         */
        private const val MAX_ATTEMPTS = 5

        /** Enqueues (or keeps) the unique upload job for one recording. */
        fun enqueue(context: Context, recordingId: String) {
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(workDataOf(KEY_RECORDING_ID to recordingId))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS,
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "upload-$recordingId",
                // KEEP: if a job for this recording is already queued/running,
                // this call is a no-op. Retry-after-terminal-failure still
                // works because finished work does not block a new enqueue.
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_RECORDING_ID) ?: return Result.failure()
        val repository = RecordingRepository.getInstance(applicationContext)
        val recording = repository.awaitRecording(id)
            // Discarded (or index lost) since enqueue — nothing to upload.
            ?: return Result.failure()
        // Already fully uploaded on a previous attempt (e.g. process died
        // between success and WorkManager bookkeeping) — done.
        if (recording.status == RecordingStatus.UPLOADED) return Result.success()

        return try {
            repository.performUpload(recording)
            Result.success()
        } catch (e: IOException) {
            val message = e.message ?: "upload failed"
            if (runAttemptCount + 1 >= MAX_ATTEMPTS) {
                repository.onUploadExhausted(id, message)
                Result.failure()
            } else {
                repository.onUploadRetrying(id, message)
                Result.retry()
            }
        }
    }
}
