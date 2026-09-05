package com.nogamt.showroom.media.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.system.Os
import android.util.Log
import com.nogamt.showroom.Constants
import com.nogamt.showroom.media.VideoIdMatcher
import java.io.InputStream
import java.io.OutputStream

/**
 * Storage Access Framework backend: USB drives, SD cards, or any document provider the TV
 * exposes. The staff pick the folder once; the permission is persisted and revalidated.
 *
 * Everything here tolerates the medium disappearing mid-call.
 */
class SafMediaStorage(
    context: Context,
    private val treeUriString: String
) : MediaStorage {

    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val treeUri: Uri? = runCatching { Uri.parse(treeUriString) }.getOrNull()

    override val kind = StorageKind.SAF

    override val label: String
        get() = describe(treeUriString)

    override fun permissionValid(): Boolean {
        val uri = treeUri ?: return false
        return resolver.persistedUriPermissions.any { it.isReadPermission && it.uri == uri }
    }

    override fun isAvailable(): Boolean {
        if (!permissionValid()) return false
        val children = childrenUriOf(rootDocId() ?: return false) ?: return false
        return runCatching {
            resolver.query(
                children,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null, null, null
            )?.use { true } ?: false
        }.getOrDefault(false)
    }

    override fun listVideos(): List<StoredFile> = walk().filter { entry ->
        !entry.isDirectory &&
            VideoIdMatcher.extensionOf(entry.file.name) in Constants.VIDEO_EXTENSIONS &&
            !entry.file.name.startsWith(".") &&
            !entry.file.name.endsWith(Constants.PART_SUFFIX)
    }.map { it.file }

    override fun listPartFiles(): List<StoredFile> = walk()
        .filter { !it.isDirectory && it.file.name.endsWith(Constants.PART_SUFFIX) }
        .map { it.file }

    override fun isReadable(uri: String): Boolean {
        if (!permissionValid()) return false
        return runCatching {
            resolver.query(
                Uri.parse(uri),
                arrayOf(DocumentsContract.Document.COLUMN_SIZE),
                null, null, null
            )?.use { c ->
                c.moveToFirst() && (c.isNull(0) || c.getLong(0) > 0L)
            } ?: false
        }.getOrDefault(false)
    }

    override fun openRead(uri: String): InputStream? =
        runCatching { resolver.openInputStream(Uri.parse(uri)) }.getOrNull()

    override fun findByName(name: String): StoredFile? =
        walk().firstOrNull { !it.isDirectory && it.file.name == name }?.file

    /**
     * Document providers do not expose free space directly. Probe it by creating a tiny file,
     * asking the filesystem behind its descriptor, then deleting the probe. Returns null when
     * the provider does not allow that (some network providers) - the caller then skips the
     * capacity check rather than blocking a download.
     */
    override fun freeSpaceBytes(): Long? {
        val parent = rootDocUri() ?: return null
        var probe: Uri? = null
        return try {
            probe = DocumentsContract.createDocument(
                resolver, parent, "application/octet-stream", ".nogamt-space-probe"
            ) ?: return null
            resolver.openFileDescriptor(probe, "r")?.use { pfd ->
                val stat = Os.fstatvfs(pfd.fileDescriptor)
                stat.f_bavail * stat.f_frsize
            }
        } catch (t: Throwable) {
            Log.i(Constants.LOG, "Free space unavailable on this provider: ${t.message}")
            null
        } finally {
            probe?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
        }
    }

    override fun openPart(finalName: String): PartFile? {
        val parent = rootDocUri() ?: return null
        val partName = finalName + Constants.PART_SUFFIX
        val existing = findByName(partName)
        val uri = existing?.uri ?: runCatching {
            DocumentsContract.createDocument(
                resolver, parent, "application/octet-stream", partName
            )?.toString()
        }.getOrNull() ?: return null

        // Providers name-collide by appending "(1)" - reject anything that came back renamed.
        val actualName = queryName(uri) ?: partName
        val appendSupported = supportsAppend(uri)
        return PartFile(
            finalName = finalName,
            partName = actualName,
            uri = uri,
            existingBytes = if (appendSupported) querySize(uri) else 0L,
            appendSupported = appendSupported
        )
    }

    override fun openPartOutput(part: PartFile, append: Boolean): OutputStream? = runCatching {
        val mode = if (append && part.appendSupported) "wa" else "w"
        resolver.openOutputStream(Uri.parse(part.uri), mode)
    }.getOrNull()

    override fun partSize(part: PartFile): Long = querySize(part.uri)

    override fun promotePart(part: PartFile): String? {
        // Remove any previous copy only now - the replacement is already verified.
        findByName(part.finalName)?.let { previous ->
            runCatching { DocumentsContract.deleteDocument(resolver, Uri.parse(previous.uri)) }
                .onFailure { Log.w(Constants.LOG, "Could not delete previous ${part.finalName}") }
        }
        return runCatching {
            DocumentsContract.renameDocument(resolver, Uri.parse(part.uri), part.finalName)
                ?.toString()
        }.getOrNull()
    }

    override fun deletePart(part: PartFile): Boolean = delete(part.uri)

    override fun delete(uri: String): Boolean = runCatching {
        DocumentsContract.deleteDocument(resolver, Uri.parse(uri))
    }.getOrDefault(false)

    override fun importFile(name: String, source: InputStream, expectedSize: Long): Boolean {
        val part = openPart(name) ?: return false
        val ok = runCatching {
            openPartOutput(part, append = false)?.use { out ->
                source.use { input -> input.copyTo(out) }
            } != null
        }.getOrDefault(false)
        if (!ok) { deletePart(part); return false }
        if (expectedSize > 0L && partSize(part) != expectedSize) {
            deletePart(part)
            return false
        }
        return promotePart(part) != null
    }

    // ---------------------------------------------------------------- internals

    private data class Entry(val file: StoredFile, val isDirectory: Boolean, val docId: String)

    private fun rootDocId(): String? =
        treeUri?.let { runCatching { DocumentsContract.getTreeDocumentId(it) }.getOrNull() }

    private fun rootDocUri(): Uri? {
        val tree = treeUri ?: return null
        val docId = rootDocId() ?: return null
        return runCatching { DocumentsContract.buildDocumentUriUsingTree(tree, docId) }.getOrNull()
    }

    private fun childrenUriOf(docId: String): Uri? {
        val tree = treeUri ?: return null
        return runCatching {
            DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        }.getOrNull()
    }

    private fun queryName(uri: String): String? = runCatching {
        resolver.query(
            Uri.parse(uri),
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    private fun querySize(uri: String): Long = runCatching {
        resolver.query(
            Uri.parse(uri),
            arrayOf(DocumentsContract.Document.COLUMN_SIZE),
            null, null, null
        )?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L } ?: 0L
    }.getOrDefault(0L)

    private fun supportsAppend(uri: String): Boolean = runCatching {
        resolver.query(
            Uri.parse(uri),
            arrayOf(DocumentsContract.Document.COLUMN_FLAGS),
            null, null, null
        )?.use { c ->
            if (!c.moveToFirst()) false
            else (c.getInt(0) and DocumentsContract.Document.FLAG_SUPPORTS_WRITE) != 0
        } ?: false
    }.getOrDefault(false)

    /** Breadth-first walk of the tree, tolerant of the medium vanishing mid-scan. */
    private fun walk(): List<Entry> {
        val tree = treeUri ?: return emptyList()
        if (!permissionValid()) return emptyList()
        val rootId = rootDocId() ?: return emptyList()

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )

        val out = ArrayList<Entry>()
        val queue = ArrayDeque<Pair<String, Int>>()
        queue.add(rootId to 0)

        while (queue.isNotEmpty() && out.size < Constants.SCAN_MAX_FILES) {
            val (docId, depth) = queue.removeFirst()
            if (depth > Constants.SCAN_MAX_DEPTH) continue
            val childrenUri = childrenUriOf(docId) ?: continue

            val cursor = try {
                resolver.query(childrenUri, projection, null, null, null)
            } catch (t: Throwable) {
                Log.w(Constants.LOG, "SAF walk interrupted: ${t.message}")
                null
            } ?: continue

            cursor.use { c ->
                while (c.moveToNext()) {
                    val childId = c.getString(0) ?: continue
                    val name = c.getString(1).orEmpty()
                    val mime = c.getString(2).orEmpty()
                    val size = if (c.isNull(3)) 0L else c.getLong(3)
                    val modified = if (c.isNull(4)) 0L else c.getLong(4)
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR

                    if (isDir) {
                        if (!name.startsWith(".")) queue.add(childId to depth + 1)
                        continue
                    }
                    val uri = runCatching {
                        DocumentsContract.buildDocumentUriUsingTree(tree, childId).toString()
                    }.getOrNull() ?: continue
                    out.add(Entry(StoredFile(name, uri, size, modified), false, childId))
                }
            }
        }
        return out
    }

    companion object {
        /** Human readable source name for the staff screens. */
        fun describe(treeUriString: String?): String {
            if (treeUriString.isNullOrBlank()) return "No folder selected"
            val uri = runCatching { Uri.parse(treeUriString) }.getOrNull() ?: return "Unknown"
            val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            val authority = uri.authority.orEmpty()
            val readable = if (docId.isNullOrBlank()) {
                uri.lastPathSegment.orEmpty()
            } else {
                docId.substringAfterLast(':').ifBlank { docId }
            }
            val kind = when {
                docId?.startsWith("primary:") == true -> "Internal (device) storage"
                authority.contains("externalstorage") -> "USB / external drive"
                else -> "Document provider"
            }
            return "$kind · $readable"
        }
    }
}
