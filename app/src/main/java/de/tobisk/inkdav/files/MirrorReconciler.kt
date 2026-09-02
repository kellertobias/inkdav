package de.tobisk.inkdav.files

enum class MirrorDecision { BASELINE_EQUAL, DOWNLOAD, UPLOAD, DELETE_LOCAL, DELETE_REMOTE, REMOVE_ENTRY, CONFLICT, NO_CHANGE }

object MirrorReconciler {
    fun decide(
        baselined: Boolean,
        baselineRemote: String?,
        baselineLocal: String?,
        remote: String?,
        local: String?,
        contentsEqual: Boolean = false
    ): MirrorDecision {
        if (!baselined) {
            return when {
                remote == null && local == null -> MirrorDecision.NO_CHANGE
                remote == null -> MirrorDecision.UPLOAD
                local == null -> MirrorDecision.DOWNLOAD
                contentsEqual -> MirrorDecision.BASELINE_EQUAL
                else -> MirrorDecision.CONFLICT
            }
        }
        val remoteChanged = remote != baselineRemote
        val localChanged = local != baselineLocal
        return when {
            remote == null && local == null -> MirrorDecision.REMOVE_ENTRY
            remote == null && !localChanged -> MirrorDecision.DELETE_LOCAL
            local == null && !remoteChanged -> MirrorDecision.DELETE_REMOTE
            remote == null || local == null -> MirrorDecision.CONFLICT
            !remoteChanged && !localChanged -> MirrorDecision.NO_CHANGE
            remoteChanged && !localChanged -> MirrorDecision.DOWNLOAD
            !remoteChanged && localChanged -> MirrorDecision.UPLOAD
            contentsEqual -> MirrorDecision.BASELINE_EQUAL
            else -> MirrorDecision.CONFLICT
        }
    }
}
