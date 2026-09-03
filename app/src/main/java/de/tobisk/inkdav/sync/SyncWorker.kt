package de.tobisk.inkdav.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import de.tobisk.inkdav.InkDavApplication
import de.tobisk.inkdav.widgets.WidgetUpdater
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        setProgress(progressData(0, 0, null))
        val succeeded = (applicationContext as InkDavApplication).container.syncEngine.synchronizeAll(
            inputData.getBoolean(INCLUDE_FILES, false)
        ) { progress ->
            setProgress(progressData(progress.completedAccounts, progress.totalAccounts, progress.accountName))
        }
        return if (succeeded) {
            WidgetUpdater.updateAll(applicationContext)
            Result.success()
        } else {
            Result.retry()
        }
    }

    companion object {
        private const val ONE_TIME_NAME = "inkdav-sync-now"
        private const val PERIODIC_NAME = "inkdav-sync-periodic"

        fun enqueue(context: Context): UUID {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(workDataOf(INCLUDE_FILES to true))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofMinutes(1))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(ONE_TIME_NAME, MANUAL_SYNC_POLICY, request)
            return request.id
        }

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
                .setInputData(workDataOf(INCLUDE_FILES to false))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofMinutes(15))
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        private const val INCLUDE_FILES = "include-files"
        internal const val PROGRESS_COMPLETED = "progress-completed"
        internal const val PROGRESS_TOTAL = "progress-total"
        internal const val PROGRESS_ACCOUNT = "progress-account"

        internal val MANUAL_SYNC_POLICY = ExistingWorkPolicy.REPLACE

        private fun progressData(completed: Int, total: Int, accountName: String?) = workDataOf(
            PROGRESS_COMPLETED to completed,
            PROGRESS_TOTAL to total,
            PROGRESS_ACCOUNT to accountName
        )
    }
}
