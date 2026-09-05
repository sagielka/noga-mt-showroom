package com.nogamt.showroom.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.nogamt.showroom.Constants
import com.nogamt.showroom.Prefs
import com.nogamt.showroom.StorageMode
import com.nogamt.showroom.media.storage.InternalMediaStorage
import com.nogamt.showroom.media.storage.MediaStorage
import com.nogamt.showroom.media.storage.SafMediaStorage
import com.nogamt.showroom.media.storage.StorageKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the storage backend, scanning and the schedule of synchronisation.
 * Every screen goes through here so there is exactly one code path.
 */
object MediaRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var cachedStorage: MediaStorage? = null
    @Volatile private var cachedFor: String = ""

    /** The active storage backend, or null when the staff skipped setup. */
    fun storage(context: Context): MediaStorage? {
        val prefs = Prefs.get(context)
        val key = "${prefs.storageMode}:${prefs.mediaTreeUri}"
        val existing = cachedStorage
        if (existing != null && cachedFor == key) return existing

        val created: MediaStorage? = when (prefs.storageMode) {
            StorageMode.INTERNAL -> InternalMediaStorage(context)
            StorageMode.SAF -> prefs.mediaTreeUri?.let { SafMediaStorage(context, it) }
            StorageMode.NONE -> null
        }
        cachedStorage = created
        cachedFor = key
        MediaIndex.readabilityProbe = created?.let { store -> { uri -> store.isReadable(uri) } }
        return created
    }

    fun hasStorage(context: Context): Boolean = storage(context) != null

    fun storageLabel(context: Context): String =
        storage(context)?.label ?: "No storage selected"

    fun storageKind(context: Context): StorageKind? = storage(context)?.kind

    // ---------------------------------------------------------------- setup

    /** Persists read/write permission for a freshly picked SAF tree. */
    fun adoptSafFolder(context: Context, treeUri: Uri): Boolean = try {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        val prefs = Prefs.get(context)
        prefs.mediaTreeUri = treeUri.toString()
        prefs.mediaTreeLabel = SafMediaStorage.describe(treeUri.toString())
        prefs.storageMode = StorageMode.SAF
        prefs.firstRunCompleted = true
        invalidate()
        Log.i(Constants.LOG, "Adopted SAF media folder: ${prefs.mediaTreeLabel}")
        true
    } catch (t: Throwable) {
        Log.e(Constants.LOG, "Could not persist folder permission", t)
        false
    }

    fun useInternalStorage(context: Context) {
        val prefs = Prefs.get(context)
        prefs.storageMode = StorageMode.INTERNAL
        prefs.firstRunCompleted = true
        invalidate()
        Log.i(Constants.LOG, "Using internal storage for local media")
    }

    fun skipStorageSetup(context: Context) {
        val prefs = Prefs.get(context)
        prefs.firstRunCompleted = true
        Log.i(Constants.LOG, "Staff skipped offline media setup - online playback only")
    }

    fun invalidate() {
        cachedStorage = null
        cachedFor = ""
        MediaIndex.invalidateReadability()
    }

    // ---------------------------------------------------------------- scanning

    /** Rescans the selected location. [onFinished] runs on the main thread. */
    fun rescan(context: Context, onFinished: ((MediaScanner.ScanResult?) -> Unit)? = null) {
        val appContext = context.applicationContext
        val prefs = Prefs.get(appContext)
        if (MediaIndex.scanning) {
            onFinished?.invoke(null)
            return
        }
        MediaIndex.scanning = true
        scope.launch {
            val result = runCatching { scanNow(appContext, prefs) }
                .getOrElse {
                    Log.e(Constants.LOG, "Media scan failed", it)
                    MediaScanner.ScanResult.unavailable(storageLabel(appContext))
                }
            MediaIndex.replace(result, prefs)
            MediaIndex.scanning = false
            withContext(Dispatchers.Main) { onFinished?.invoke(result) }
        }
    }

    private fun scanNow(context: Context, prefs: Prefs): MediaScanner.ScanResult {
        val store = storage(context)
            ?: return MediaScanner.ScanResult.unavailable("No storage selected")
        MediaIndex.permissionValid = store.permissionValid()
        val available = store.isAvailable()
        MediaIndex.freeBytes = if (available) store.freeSpaceBytes() else null
        if (!available) return MediaScanner.ScanResult.unavailable(store.label)

        val known = MediaIndex.all().associateBy { it.id }
        return MediaScanner.index(
            files = store.listVideos(),
            sourceLabel = store.label,
            sourceAvailable = true,
            known = known
        )
    }

    /**
     * Cheap liveness check for app resume and USB hot-plug. Marks the source unavailable
     * (so hasLocalVideo() returns false and Lovable falls back online) without wiping the
     * index, then rescans automatically when the medium comes back.
     */
    fun verifySourceAvailability(context: Context, onFinished: ((Boolean) -> Unit)? = null) {
        val appContext = context.applicationContext
        val prefs = Prefs.get(appContext)
        scope.launch {
            val store = storage(appContext)
            val permission = store?.permissionValid() ?: false
            val available = store?.isAvailable() ?: false
            val was = MediaIndex.sourceAvailable
            MediaIndex.permissionValid = permission
            MediaIndex.setSourceAvailable(available)

            if (available && !was) {
                Log.i(Constants.LOG, "Media source restored - rescanning")
                val result = runCatching { scanNow(appContext, prefs) }.getOrNull()
                if (result != null) MediaIndex.replace(result, prefs)
                store?.let { SyncEngine.cleanStaleParts(it) }
                SyncEngine.sync(appContext, store)
            } else if (!available && was) {
                Log.w(Constants.LOG, "Media source unavailable - local playback disabled")
            }
            withContext(Dispatchers.Main) { onFinished?.invoke(available) }
        }
    }

    // ---------------------------------------------------------------- sync

    fun syncNow(context: Context, force: Boolean = true, onFinished: (() -> Unit)? = null) {
        val appContext = context.applicationContext
        SyncEngine.sync(appContext, storage(appContext), force) {
            // A completed pass may have added files: refresh availability counters cheaply.
            MediaIndex.freeBytes = storage(appContext)?.freeSpaceBytes()
            onFinished?.invoke()
        }
    }

    // ---------------------------------------------------------------- storage change

    enum class SwitchMode { USE_NEW, COPY_EXISTING, START_FRESH }

    /**
     * Switches storage destination. Never deletes the old library - START_FRESH only clears
     * the index, the previous files stay where they are.
     */
    fun switchStorage(
        context: Context,
        newMode: StorageMode,
        newTreeUri: Uri?,
        switchMode: SwitchMode,
        onProgress: ((String) -> Unit)? = null,
        onFinished: ((Boolean) -> Unit)? = null
    ) {
        val appContext = context.applicationContext
        val prefs = Prefs.get(appContext)
        val oldStorage = storage(appContext)

        scope.launch {
            var ok = true
            val previousFiles = if (switchMode == SwitchMode.COPY_EXISTING && oldStorage != null) {
                runCatching { oldStorage.listVideos() }.getOrDefault(emptyList())
            } else {
                emptyList()
            }

            when (newMode) {
                StorageMode.INTERNAL -> useInternalStorage(appContext)
                StorageMode.SAF -> {
                    val uri = newTreeUri
                    ok = uri != null && adoptSafFolder(appContext, uri)
                }
                StorageMode.NONE -> {
                    prefs.clearStorageSelection()
                    invalidate()
                }
            }

            val target = storage(appContext)
            if (ok && switchMode == SwitchMode.COPY_EXISTING && oldStorage != null && target != null) {
                var copied = 0
                for (file in previousFiles) {
                    val stream = oldStorage.openRead(file.uri)
                    if (stream == null) continue
                    withContext(Dispatchers.Main) {
                        onProgress?.invoke("Copying ${copied + 1}/${previousFiles.size}: ${file.name}")
                    }
                    if (target.importFile(file.name, stream, file.sizeBytes)) copied++
                }
                Log.i(Constants.LOG, "Copied $copied/${previousFiles.size} files to new storage")
            }

            if (switchMode == SwitchMode.START_FRESH) {
                MediaIndex.clear(prefs)
                prefs.localLibraryVersion = -1L
            }

            val result = runCatching { scanNow(appContext, prefs) }.getOrNull()
            if (result != null) MediaIndex.replace(result, prefs)
            withContext(Dispatchers.Main) { onFinished?.invoke(ok) }
            SyncEngine.sync(appContext, storage(appContext), force = true)
        }
    }

    // ---------------------------------------------------------------- cleanup

    data class CleanupPlan(val videos: List<LocalVideo>, val totalBytes: Long)

    /** What CLEAN UNUSED MEDIA would remove. Nothing is deleted by calling this. */
    fun planCleanup(): CleanupPlan {
        val playing = SyncEngine.currentlyPlayingId
        val downloading = MediaIndex.downloadingIds()
        val candidates = MediaIndex.unusedVideos()
            .filter { it.id != playing && !downloading.contains(it.id) }
        return CleanupPlan(candidates, candidates.sumOf { it.sizeBytes })
    }

    /** Executes a previously shown plan after explicit staff confirmation. */
    fun runCleanup(context: Context, plan: CleanupPlan, onFinished: ((Int) -> Unit)? = null) {
        val appContext = context.applicationContext
        val prefs = Prefs.get(appContext)
        scope.launch {
            val store = storage(appContext)
            var removed = 0
            if (store != null) {
                for (video in plan.videos) {
                    if (video.id == SyncEngine.currentlyPlayingId) continue
                    if (MediaIndex.downloadingIds().contains(video.id)) continue
                    if (store.delete(video.uri)) {
                        MediaIndex.removeById(video.id, prefs)
                        removed++
                        Log.i(Constants.LOG, "Deleted unused media ${video.fileName}")
                    }
                }
            }
            val result = runCatching { scanNow(appContext, prefs) }.getOrNull()
            if (result != null) MediaIndex.replace(result, prefs)
            withContext(Dispatchers.Main) { onFinished?.invoke(removed) }
        }
    }

    /** Staff-only low-storage flag. Never shown to visitors. */
    fun lowStorage(): Boolean {
        val free = MediaIndex.freeBytes ?: return false
        return free < Constants.LOW_STORAGE_BYTES
    }
}
