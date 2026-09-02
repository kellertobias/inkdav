package de.tobisk.inkdav.files

import org.junit.Assert.assertEquals
import org.junit.Test

class MirrorReconcilerTest {
    @Test fun firstSyncNeverDeletesAndConflictsOnDifferentCollisions() {
        assertEquals(MirrorDecision.DOWNLOAD, MirrorReconciler.decide(false, null, null, "r", null))
        assertEquals(MirrorDecision.UPLOAD, MirrorReconciler.decide(false, null, null, null, "l"))
        assertEquals(MirrorDecision.CONFLICT, MirrorReconciler.decide(false, null, null, "r", "l", false))
    }

    @Test fun threeWayMatrixProtectsConcurrentChangesAndPropagatesCleanDeletes() {
        assertEquals(MirrorDecision.DOWNLOAD, MirrorReconciler.decide(true, "r0", "l0", "r1", "l0"))
        assertEquals(MirrorDecision.UPLOAD, MirrorReconciler.decide(true, "r0", "l0", "r0", "l1"))
        assertEquals(MirrorDecision.CONFLICT, MirrorReconciler.decide(true, "r0", "l0", "r1", "l1"))
        assertEquals(MirrorDecision.DELETE_LOCAL, MirrorReconciler.decide(true, "r0", "l0", null, "l0"))
        assertEquals(MirrorDecision.DELETE_REMOTE, MirrorReconciler.decide(true, "r0", "l0", "r0", null))
    }
}
