package com.nogamt.showroom.media

/**
 * Decides the state of every video by comparing the remote manifest with the local index.
 *
 * Pure logic, no Android APIs, so the rules are unit testable. This resolver produces a
 * lookup/status map - it never orders anything and never decides what plays next.
 */
object MediaStateResolver {

    data class Resolution(
        val states: Map<String, MediaState>,
        /** Ids that should be downloaded now, in manifest order (download order only). */
        val toDownload: List<String>,
        val counts: Map<MediaState, Int>
    ) {
        fun count(state: MediaState): Int = counts[state] ?: 0
    }

    /**
     * A local copy is considered stale when the manifest says so. Files copied in by hand have
     * version 0 and no checksum - those are trusted when their size matches (or when the
     * manifest gives no size), so a manual USB library is never re-downloaded for nothing.
     */
    fun needsUpdate(local: LocalVideo, remote: RemoteVideo): Boolean {
        if (local.version > 0L && remote.version > local.version) return true
        if (remote.fileSize > 0L && local.sizeBytes > 0L && local.sizeBytes != remote.fileSize) {
            return true
        }
        val remoteSum = remote.sha256
        val localSum = local.sha256
        if (!remoteSum.isNullOrBlank() && !localSum.isNullOrBlank() &&
            !remoteSum.equals(localSum, ignoreCase = true)
        ) return true
        return false
    }

    fun resolve(
        remote: List<RemoteVideo>,
        local: Map<String, LocalVideo>,
        sourceAvailable: Boolean,
        downloading: Set<String> = emptySet(),
        failed: Set<String> = emptySet()
    ): Resolution {
        val states = LinkedHashMap<String, MediaState>()
        val toDownload = ArrayList<String>()
        val effectiveLocal = if (sourceAvailable) local else emptyMap()

        for (video in remote) {
            if (!video.enabled) {
                // Disabled remotely but still on disk: keep the file, flag it for staff.
                if (effectiveLocal.containsKey(video.id)) states[video.id] = MediaState.UNUSED
                continue
            }

            val localCopy = effectiveLocal[video.id]
            val state = when {
                downloading.contains(video.id) -> MediaState.DOWNLOADING
                localCopy != null && !needsUpdate(localCopy, video) -> MediaState.LOCAL_READY
                localCopy != null && failed.contains(video.id) -> MediaState.FAILED
                localCopy != null -> MediaState.UPDATE_AVAILABLE
                failed.contains(video.id) -> MediaState.FAILED
                !video.downloadable -> MediaState.ONLINE_ONLY
                else -> MediaState.MISSING
            }
            states[video.id] = state

            if ((state == MediaState.MISSING || state == MediaState.UPDATE_AVAILABLE) &&
                video.downloadable
            ) {
                toDownload.add(video.id)
            }
        }

        // Local files the remote library no longer lists. Kept, never auto-deleted.
        val remoteIds = remote.map { it.id }.toSet()
        for (id in effectiveLocal.keys) {
            if (!remoteIds.contains(id)) states[id] = MediaState.UNUSED
        }

        val counts = HashMap<MediaState, Int>()
        states.values.forEach { counts[it] = (counts[it] ?: 0) + 1 }

        return Resolution(states, toDownload, counts)
    }
}
