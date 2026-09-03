package de.tobisk.inkdav.sync

import androidx.work.ExistingWorkPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncWorkerTest {
    @Test
    fun `manual sync replaces a failed job waiting for retry`() {
        assertEquals(ExistingWorkPolicy.REPLACE, SyncWorker.MANUAL_SYNC_POLICY)
    }
}
