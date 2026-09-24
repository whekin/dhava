package com.nakvali.core.recording.bikeyard

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

/** Separate from ride upload: a rejected candidate document never invalidates its track. */
class BikeyardMetricsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val key = inputData.getString("key") ?: return Result.failure()
        return if (BikeyardRepository.getInstance(applicationContext).processMetrics(key, runAttemptCount)) {
            Result.success()
        } else {
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "bikeyard-metrics"
        fun enqueue(context: Context, key: String, replace: Boolean = false) {
            val job = OneTimeWorkRequestBuilder<BikeyardMetricsWorker>()
                .setInputData(workDataOf("key" to key)).addTag(TAG)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "bikeyard-metrics-$key",
                if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                job,
            )
        }
        fun cancel(context: Context, key: String) {
            WorkManager.getInstance(context).cancelUniqueWork("bikeyard-metrics-$key")
        }
        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
        }
    }
}
