package de.tobisk.inkdav.sync

import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.workDataOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncWorkerTest {
    @Test
    fun `manual sync replaces a failed job waiting for retry`() {
        assertEquals(ExistingWorkPolicy.REPLACE, SyncWorker.MANUAL_SYNC_POLICY)
    }

    @Test
    fun `running sync reports the real account and completion count`() {
        val state = manualSyncState(
            WorkInfo.State.RUNNING,
            0,
            workDataOf(
                SyncWorker.PROGRESS_COMPLETED to 1,
                SyncWorker.PROGRESS_TOTAL to 3,
                SyncWorker.PROGRESS_ACCOUNT to "Personal"
            )
        )

        assertTrue(state.active)
        assertEquals("Syncing Personal (2 of 3)…", state.label)
        assertTrue(state.progress > 0.3f)
    }

    @Test
    fun `failed retry remains visible instead of looking up to date`() {
        val state = manualSyncState(WorkInfo.State.ENQUEUED, 1, workDataOf())

        assertFalse(state.active)
        assertEquals("Sync failed — retry scheduled", state.label)
    }
}
