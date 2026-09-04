package com.nogamt.showroom.media

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.nogamt.showroom.Constants

/**
 * Walks a staff-selected SAF tree (internal folder, USB drive, network mount - whatever the
 * platform exposes through the document provider) and builds the local media index.
 *
 * Deliberately uses DocumentsContract cursors rather than DocumentFile: a 600 MB USB stick
 * with 30 files scans in well under a second, and it does not allocate an object per entry.
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

    private val PROJECTION = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED
    )

    fun scan(context: Context, treeUriString: String?): ScanResult {
        val label = describe(treeUriString)
        if (treeUriString.isNullOrBlank()) return ScanResult.unavailable("No folder selected")

        val treeUri = runCatching { Uri.parse(treeUriString) }.getOrNull()
            ?: return ScanResult.unavailable(label)

        if (!hasPermission(context, treeUri)) {
            Log.w(Constants.LOG, "No persisted permission for $treeUri")
            return ScanResult.unavailable("$label (permission lost)")
        }

        val videos = LinkedHashMap<String, LocalVideo>()
        val unmatched = ArrayList<UnmatchedFile>()
        val duplicates = ArrayList<DuplicateId>()
        var totalFiles = 0
        var totalBytes = 0L

        val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return ScanResult.unavailable(label)

        val queue = ArrayDeque<Pair<String, Int>>()
        queue.add(rootDocId to 0)

        var mediaUnreachable = false

        while (queue.isNotEmpty() && totalFiles < Constants.SCAN_MAX_FILES) {
            val (docId, depth) = queue.removeFirst()
            if (depth > Constants.SCAN_MAX_DEPTH) continue

            val childrenUri = runCatching {
                DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            }.getOrNull() ?: continue

            val cursor = try {
                context.contentResolver.query(childrenUri, PROJECTION, null, null, null)
            } catch (t: Throwable) {
                // USB pulled mid-scan, provider died, permission revoked...
                Log.w(Constants.LOG, "Scan query failed for $docId: ${t.message}")
                mediaUnreachable = true
                null
            } ?: continue

            cursor.use { c ->
                val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val modIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                while (c.moveToNext()) {
                    val childId = (if (idIdx >= 0) c.getString(idIdx) else null) ?: continue
                    val name = if (nameIdx >= 0) c.getString(nameIdx).orEmpty() else ""
                    val mime = if (mimeIdx >= 0) c.getString(mimeIdx).orEmpty() else ""
                    val size = if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else 0L
                    val modified = if (modIdx >= 0 && !c.isNull(modIdx)) c.getLong(modIdx) else 0L

                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (!name.startsWith(".")) queue.add(childId to depth + 1)
                        continue
                    }

                    val ext = VideoIdMatcher.extensionOf(name)
                    if (ext !in Constants.VIDEO_EXTENSIONS) continue
                    if (name.startsWith(".")) continue

                    totalFiles++
                    totalBytes += size

                    val fileUri = DocumentsContract
                        .buildDocumentUriUsingTree(treeUri, childId)
                        .toString()

                    val match = VideoIdMatcher.match(name)
                    if (match == null) {
                        unmatched.add(UnmatchedFile(name, fileUri, size))
                        continue
                    }

                    val existing = videos[match.id]
                    if (existing != null) {
                        // Same id twice: keep the first, report the second. No duplicate entry.
                        duplicates.add(DuplicateId(match.id, existing.fileName, name))
                        continue
                    }

                    videos[match.id] = LocalVideo(
                        id = match.id,
                        uri = fileUri,
                        fileName = name,
                        sizeBytes = size,
                        lastModified = modified,
                        matchType = match.type
                    )
                }
            }
        }

        val available = !mediaUnreachable || totalFiles > 0
        return ScanResult(
            videos = videos.values.toList(),
            unmatched = unmatched,
            duplicates = duplicates,
            totalFiles = totalFiles,
            totalBytes = totalBytes,
            sourceLabel = label,
            sourceAvailable = available
        )
    }

    /** True when the tree URI is still readable (USB present, permission alive). */
    fun isSourceReachable(context: Context, treeUriString: String?): Boolean {
        val uri = treeUriString?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return false
        if (!hasPermission(context, uri)) return false
        return try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, docId)
            context.contentResolver.query(children, arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID
            ), null, null, null)?.use { true } ?: false
        } catch (t: Throwable) {
            false
        }
    }

    fun hasPermission(context: Context, treeUri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any {
            it.isReadPermission && it.uri == treeUri
        }

    /** Human readable source name for the staff screens. */
    fun describe(treeUriString: String?): String {
        if (treeUriString.isNullOrBlank()) return "No folder selected"
        val uri = runCatching { Uri.parse(treeUriString) }.getOrNull() ?: return "Unknown"
        val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        val authority = uri.authority ?: ""
        val readable = if (docId.isNullOrBlank()) {
            uri.lastPathSegment.orEmpty()
        } else {
            docId.substringAfterLast(':').ifBlank { docId }
        }
        val kind = when {
            docId?.startsWith("primary:") == true -> "Internal storage"
            authority.contains("externalstorage") -> "USB / external"
            else -> "Document provider"
        }
        return "$kind · $readable"
    }

    fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> String.format("%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
        bytes >= 1024L * 1024L -> String.format("%.0f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024L -> String.format("%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
