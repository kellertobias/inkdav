package de.tobisk.inkdav.sync

import androidx.work.Data
import androidx.work.WorkInfo
import de.tobisk.inkdav.data.DavAccountEntity

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

data class SyncHeaderStatus(val label: String, val warning: Boolean)

/** Compact status for headers; stored per-account errors outrank a stale "Sync complete" or an idle "Up to date". */
internal fun syncHeaderStatus(syncState: ManualSyncState, pending: Int, accounts: List<DavAccountEntity>): SyncHeaderStatus? {
    val enabled = accounts.filter { it.enabled }
    val failing = enabled.count { it.lastSyncError != null }
    val label = syncState.label
    return when {
        label != null && syncState.active -> SyncHeaderStatus(label, warning = false)
        failing == 1 -> SyncHeaderStatus("Sync error", warning = true)
        failing > 1 -> SyncHeaderStatus("$failing sync errors", warning = true)
        label != null -> SyncHeaderStatus(label, warning = label.contains("failed", ignoreCase = true))
        pending > 0 -> SyncHeaderStatus("$pending waiting", warning = true)
        enabled.any { it.lastSyncAt == null } -> SyncHeaderStatus("Not synced yet", warning = false)
        enabled.isNotEmpty() -> SyncHeaderStatus("Up to date", warning = false)
        else -> null
    }
}
