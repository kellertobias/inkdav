package de.tobisk.inkdav.sync

import androidx.work.Data
import androidx.work.WorkInfo

data class ManualSyncState(
    val active: Boolean = false,
    val label: String? = null,
    val progress: Float = 0f
)

internal fun manualSyncState(
    state: WorkInfo.State,
    runAttemptCount: Int,
    progressData: Data
): ManualSyncState {
    val completed = progressData.getInt(SyncWorker.PROGRESS_COMPLETED, 0)
    val total = progressData.getInt(SyncWorker.PROGRESS_TOTAL, 0)
    val account = progressData.getString(SyncWorker.PROGRESS_ACCOUNT)
    val fraction = if (total > 0) completed.toFloat() / total else 0f

    return when (state) {
        WorkInfo.State.ENQUEUED -> if (runAttemptCount > 0) {
            ManualSyncState(label = "Sync failed — retry scheduled", progress = fraction)
        } else {
            ManualSyncState(active = true, label = "Waiting for network…", progress = 0.08f)
        }
        WorkInfo.State.BLOCKED -> ManualSyncState(active = true, label = "Sync queued…", progress = 0.08f)
        WorkInfo.State.RUNNING -> ManualSyncState(
            active = true,
            label = when {
                account != null && total > 0 -> "Syncing $account (${completed + 1} of $total)…"
                total == 0 -> "Checking accounts…"
                else -> "Finishing sync…"
            },
            progress = if (total > 0) 0.12f + (fraction * 0.8f) else 0.12f
        )
        WorkInfo.State.SUCCEEDED -> ManualSyncState(label = "Sync complete", progress = 1f)
        WorkInfo.State.FAILED -> ManualSyncState(label = "Sync failed", progress = fraction)
        WorkInfo.State.CANCELLED -> ManualSyncState(label = "Sync cancelled", progress = fraction)
    }
}
