package com.nogamt.showroom.media.storage

import java.io.InputStream
import java.io.OutputStream

/** A video file that exists in the chosen storage location. */
data class StoredFile(
    val name: String,
    val uri: String,
    val sizeBytes: Long,
    val lastModified: Long
)

/** A download in progress, stored as "<final name>.part" next to the finished files. */
data class PartFile(
    val finalName: String,
    val partName: String,
    val uri: String,
    val existingBytes: Long,
    val appendSupported: Boolean
)

enum class StorageKind { INTERNAL, SAF }

/**
 * One interface over the two supported destinations: app-managed internal storage and a
 * staff-selected Storage Access Framework tree (USB drive, SD card, network provider).
 *
 * Every method is safe to call when the medium has vanished - they return false/null/empty
 * rather than throwing, so pulling a USB drive can never crash the showroom.
 */
interface MediaStorage {

    val kind: StorageKind

    /** Human label for the staff screens, e.g. "USB / external · videos". */
    val label: String

    /** SAF only: is the persisted URI permission still granted? Internal is always true. */
    fun permissionValid(): Boolean

    /** Is the medium present and readable right now? */
    fun isAvailable(): Boolean

    /** Every video file in the location, recursively. Empty when unavailable. */
    fun listVideos(): List<StoredFile>

    /** Fast readability probe for one indexed file. */
    fun isReadable(uri: String): Boolean

    fun openRead(uri: String): InputStream?

    fun findByName(name: String): StoredFile?

    /** Free space on the medium, or null when the provider will not tell us. */
    fun freeSpaceBytes(): Long?

    /** Opens (or resumes) the .part file for a download. */
    fun openPart(finalName: String): PartFile?

    /** Append stream for a .part file; if append is unsupported the part is truncated first. */
    fun openPartOutput(part: PartFile, append: Boolean): OutputStream?

    /** Current size of the .part file on disk. */
    fun partSize(part: PartFile): Long

    /**
     * Promotes a verified .part to its final name, replacing any previous copy.
     * Returns the final URI, or null on failure - the old file is only removed once the
     * replacement is verified and ready.
     */
    fun promotePart(part: PartFile): String?

    fun deletePart(part: PartFile): Boolean

    fun delete(uri: String): Boolean

    /** Removes stale .part files that no longer correspond to anything being downloaded. */
    fun listPartFiles(): List<StoredFile>

    /** Writes a copy of [name] from [source] into this storage. Used when changing storage. */
    fun importFile(name: String, source: InputStream, expectedSize: Long): Boolean
}
