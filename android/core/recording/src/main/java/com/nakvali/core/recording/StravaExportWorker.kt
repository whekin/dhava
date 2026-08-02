package com.nakvali.core.recording

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/** Network-constrained, idempotent processed-GPX delivery to Strava. */
class StravaExportWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val KEY_RECORDING_ID = "recording_id"
        private const val MAX_ATTEMPTS = 8

        private fun uniqueName(recordingId: String) = "strava-export-$recordingId"

        fun enqueue(context: Context, recordingId: String) {
            val request = OneTimeWorkRequestBuilder<StravaExportWorker>()
                .setInputData(workDataOf(KEY_RECORDING_ID to recordingId))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueName(recordingId),
                ExistingWorkPolicy.KEEP,
                request,
            )
        }

        fun cancel(context: Context, recordingId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(uniqueName(recordingId))
        }
    }

    override suspend fun doWork(): Result {
        val recordingId = inputData.getString(KEY_RECORDING_ID) ?: return Result.failure()
        val repository = RecordingRepository.getInstance(applicationContext)
        val recording = repository.awaitRecording(recordingId) ?: return Result.success()
        if (recording.stravaExportStatus == StravaExportStatus.UPLOADED) return Result.success()

        return try {
            when (repository.performStravaExport(recording)) {
                StravaExportStatus.UPLOADED -> Result.success()
                StravaExportStatus.PROCESSING,
                StravaExportStatus.QUEUED,
                -> Result.retry()
                StravaExportStatus.FAILED -> Result.failure()
            }
        } catch (error: StravaApiException) {
            if (error.code == 409) {
                repository.onStravaConnectionLost(recordingId, error.message.orEmpty())
                Result.failure()
            } else if (error.retryable && runAttemptCount < MAX_ATTEMPTS) {
                repository.onStravaExportRetry(recordingId, error.message.orEmpty())
                Result.retry()
            } else {
                repository.onStravaExportFailed(recordingId, error.message.orEmpty())
                Result.failure()
            }
        } catch (error: Exception) {
            if (runAttemptCount < MAX_ATTEMPTS) {
                repository.onStravaExportRetry(recordingId, error.message.orEmpty())
                Result.retry()
            } else {
                repository.onStravaExportFailed(recordingId, error.message.orEmpty())
                Result.failure()
            }
        }
    }
}
