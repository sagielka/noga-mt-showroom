package com.nogamt.showroom.media.storage

import android.content.Context
import android.os.StatFs
import android.util.Log
import com.nogamt.showroom.Constants
import com.nogamt.showroom.media.VideoIdMatcher
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * App-managed internal storage: <app external files dir>/NOGA-MT/videos.
 *
 * No storage permission is needed for this location, it survives reboots, and it is removed
 * when the app is uninstalled (which is the desired behaviour for an exhibition device).
 */
class InternalMediaStorage(context: Context) : MediaStorage {

    private val appContext = context.applicationContext

    private val root: File by lazy {
        val base = appContext.getExternalFilesDir(null) ?: appContext.filesDir
        File(File(base, Constants.MEDIA_DIR_NAME), Constants.VIDEO_DIR_NAME).apply { mkdirs() }
    }

    override val kind = StorageKind.INTERNAL

    override val label: String
        get() = "Internal storage · ${Constants.MEDIA_DIR_NAME}/${Constants.VIDEO_DIR_NAME}"

    override fun permissionValid(): Boolean = true

    override fun isAvailable(): Boolean = runCatching {
        if (!root.exists()) root.mkdirs()
        root.isDirectory && root.canRead()
    }.getOrDefault(false)

    override fun listVideos(): List<StoredFile> = walk().filter { file ->
        VideoIdMatcher.extensionOf(file.name) in Constants.VIDEO_EXTENSIONS &&
            !file.name.startsWith(".") && !file.name.endsWith(Constants.PART_SUFFIX)
    }.map { it.toStored() }

    override fun listPartFiles(): List<StoredFile> =
        walk().filter { it.name.endsWith(Constants.PART_SUFFIX) }.map { it.toStored() }

    override fun isReadable(uri: String): Boolean = runCatching {
        val file = File(uri.removePrefix("file://"))
        file.isFile && file.canRead() && file.length() > 0L
    }.getOrDefault(false)

    override fun openRead(uri: String): InputStream? = runCatching {
        File(uri.removePrefix("file://")).inputStream()
    }.getOrNull()

    override fun findByName(name: String): StoredFile? =
        walk().firstOrNull { it.name == name }?.toStored()

    override fun freeSpaceBytes(): Long? = runCatching {
        val stat = StatFs(root.absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong
    }.getOrNull()

    override fun openPart(finalName: String): PartFile? = runCatching {
        if (!isAvailable()) return null
        val partName = finalName + Constants.PART_SUFFIX
        val file = File(root, partName)
        if (!file.exists()) file.createNewFile()
        PartFile(
            finalName = finalName,
            partName = partName,
            uri = file.absolutePath,
            existingBytes = file.length(),
            appendSupported = true
        )
    }.getOrNull()

    override fun openPartOutput(part: PartFile, append: Boolean): OutputStream? = runCatching {
        FileOutputStream(File(part.uri), append) as OutputStream
    }.getOrNull()

    override fun partSize(part: PartFile): Long =
        runCatching { File(part.uri).length() }.getOrDefault(0L)

    override fun promotePart(part: PartFile): String? = runCatching {
        val partFile = File(part.uri)
        val target = File(root, part.finalName)
        if (target.exists() && !target.delete()) {
            Log.w(Constants.LOG, "Could not remove previous copy of ${part.finalName}")
            return null
        }
        if (partFile.renameTo(target)) target.absolutePath else null
    }.getOrNull()

    override fun deletePart(part: PartFile): Boolean =
        runCatching { File(part.uri).delete() }.getOrDefault(false)

    override fun delete(uri: String): Boolean =
        runCatching { File(uri.removePrefix("file://")).delete() }.getOrDefault(false)

    override fun importFile(name: String, source: InputStream, expectedSize: Long): Boolean =
        runCatching {
            val temp = File(root, name + Constants.PART_SUFFIX)
            source.use { input -> FileOutputStream(temp).use { out -> input.copyTo(out) } }
            if (expectedSize > 0L && temp.length() != expectedSize) {
                temp.delete()
                return false
            }
            val target = File(root, name)
            if (target.exists()) target.delete()
            temp.renameTo(target)
        }.getOrDefault(false)

    private fun walk(): List<File> {
        if (!isAvailable()) return emptyList()
        val result = ArrayList<File>()
        val queue = ArrayDeque<Pair<File, Int>>()
        queue.add(root to 0)
        while (queue.isNotEmpty() && result.size < Constants.SCAN_MAX_FILES) {
            val (dir, depth) = queue.removeFirst()
            if (depth > Constants.SCAN_MAX_DEPTH) continue
            val children = dir.listFiles() ?: continue
            for (child in children) {
                if (child.isDirectory) {
                    if (!child.name.startsWith(".")) queue.add(child to depth + 1)
                } else {
                    result.add(child)
                }
            }
        }
        return result
    }

    private fun File.toStored() = StoredFile(name, absolutePath, length(), lastModified())
}
