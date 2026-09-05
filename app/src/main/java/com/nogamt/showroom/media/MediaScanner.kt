package com.nogamt.showroom.media

import com.nogamt.showroom.media.storage.StoredFile

/**
 * Turns a flat list of files found in the chosen storage location into the lookup index.
 *
 * Pure logic - no Android APIs - so the matching and de-duplication rules are unit testable.
 * This produces an INDEX, never a playlist: order here is meaningless.
 */
object MediaScanner {

    data class ScanResult(
        val videos: List<LocalVideo>,
        val unmatched: List<UnmatchedFile>,
        val duplicates: List<DuplicateId>,
        val totalFiles: Int,
        val totalBytes: Long,
        val sourceLabel: String,
        val sourceAvailable: Boolean
    ) {
        companion object {
            fun unavailable(label: String) =
                ScanResult(emptyList(), emptyList(), emptyList(), 0, 0L, label, false)
        }
    }

    /**
     * @param known previously indexed videos, used to carry the manifest version and checksum
     *              of a file across rescans (the filesystem cannot store them).
     */
    fun index(
        files: List<StoredFile>,
        sourceLabel: String,
        sourceAvailable: Boolean,
        known: Map<String, LocalVideo> = emptyMap()
    ): ScanResult {
        val videos = LinkedHashMap<String, LocalVideo>()
        val unmatched = ArrayList<UnmatchedFile>()
        val duplicates = ArrayList<DuplicateId>()
        var totalBytes = 0L
        var totalFiles = 0

        // Deterministic winner for duplicate ids: sort by name so two scans agree.
        for (file in files.sortedBy { it.name.lowercase() }) {
            totalFiles++
            totalBytes += file.sizeBytes

            val match = VideoIdMatcher.match(file.name)
            if (match == null) {
                unmatched.add(UnmatchedFile(file.name, file.uri, file.sizeBytes))
                continue
            }

            val existing = videos[match.id]
            if (existing != null) {
                duplicates.add(DuplicateId(match.id, existing.fileName, file.name))
                continue
            }

            val previous = known[match.id]
            val sameFile = previous != null &&
                previous.fileName == file.name &&
                previous.sizeBytes == file.sizeBytes

            videos[match.id] = LocalVideo(
                id = match.id,
                uri = file.uri,
                fileName = file.name,
                sizeBytes = file.sizeBytes,
                lastModified = file.lastModified,
                matchType = match.type,
                version = if (sameFile) previous!!.version else 0L,
                sha256 = if (sameFile) previous!!.sha256 else null
            )
        }

        return ScanResult(
            videos = videos.values.toList(),
            unmatched = unmatched,
            duplicates = duplicates,
            totalFiles = totalFiles,
            totalBytes = totalBytes,
            sourceLabel = sourceLabel,
            sourceAvailable = sourceAvailable
        )
    }

    fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> String.format("%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
        bytes >= 1024L * 1024L -> String.format("%.0f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024L -> String.format("%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
