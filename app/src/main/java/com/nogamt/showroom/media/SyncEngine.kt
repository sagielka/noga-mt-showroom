package com.nogamt.showroom.media

import android.content.Context
import android.net.Uri
import android.util.Log
import com.nogamt.showroom.Constants
import com.nogamt.showroom.Prefs
import com.nogamt.showroom.media.storage.MediaStorage
import com.nogamt.showroom.media.storage.PartFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

/**
 * Keeps the local library in step with the remote media manifest.
 *
 * Rules it never breaks:
 *  - it downloads, it does not decide what plays; ordering belongs to Lovable;
 *  - a working file is never destroyed before its replacement is verified;
 *  - the file Media3 is currently reading is never touched;
 *  - nothing that failed verification is ever marked LOCAL_READY;
 *  - it never fetches from YouTube or Vimeo.
 */
object SyncEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    @Volatile private var lastManifestFetchAt = 0L
    @Volatile private var currentJob: Job? = null

    /** Set by the shell so a file in use is never replaced underneath the player. */
    @Volatile var currentlyPlayingId: String? = null

    fun interface Listener {
        fun onSyncProgress(progress: SyncProgress)
    }

    private val listeners = ArrayList<Listener>()

    fun addListener(listener: Listener) = synchronized(listeners) {
        listeners.add(listener)
        listener.onSyncProgress(MediaIndex.syncProgress)
    }

    fun removeListener(listener: Listener) = synchronized(listeners) {
        listeners.remove(listener)
    }

    private fun publish(progress: SyncProgress) {
        MediaIndex.syncProgress = progress
        val copy = synchronized(listeners) { listeners.toList() }
        copy.forEach { runCatching { it.onSyncProgress(progress) } }
    }

    val isRunning: Boolean get() = currentJob?.isActive == true

    /**
     * Runs one synchronisation pass.
     *
     * @param force ignore the minimum gap between manifest fetches (SYNC NOW).
     */
    fun sync(
        context: Context,
        storage: MediaStorage?,
        force: Boolean = false,
        onFinished: (() -> Unit)? = null
    ) {
        val appContext = context.applicationContext
        val prefs = Prefs.get(appContext)

        if (!force && !prefs.autoSync) {
            publish(SyncProgress(phase = SyncPhase.DISABLED))
            onFinished?.invoke()
            return
        }
        if (isRunning) {
            Log.i(Constants.LOG, "Sync already running, request ignored")
            onFinished?.invoke()
            return
        }

        currentJob = scope.launch {
            mutex.withLock {
                runCatching { runSync(appContext, prefs, storage, force) }
                    .onFailure {
                        Log.e(Constants.LOG, "Sync pass failed", it)
                        publish(
                            SyncProgress(
                                phase = SyncPhase.ERROR,
                                message = it.message ?: "unexpected error"
                            )
                        )
                    }
            }
            onFinished?.invoke()
        }
    }

    fun cancel() {
        currentJob?.cancel()
        currentJob = null
    }

    // ---------------------------------------------------------------- the pass

    private suspend fun runSync(
        context: Context,
        prefs: Prefs,
        storage: MediaStorage?,
        force: Boolean
    ) {
        val now = System.currentTimeMillis()
        if (!force && now - lastManifestFetchAt < Constants.SYNC_MIN_GAP_MS) {
            Log.i(Constants.LOG, "Manifest fetched recently, skipping")
            return
        }

        publish(SyncProgress(phase = SyncPhase.CHECKING, message = "checking library"))
        Log.i(Constants.LOG, "Media sync started (force=$force)")

        val fetched = fetchManifest(prefs.manifestUrl)
        if (fetched == null) {
            // Offline or manifest unpublished: keep the cached one, stay quiet, try again later.
            publish(
                SyncProgress(
                    phase = SyncPhase.ERROR,
                    message = "manifest unavailable"
                )
            )
            Log.w(Constants.LOG, "Media sync: manifest unavailable, keeping cached library")
            return
        }
        lastManifestFetchAt = System.currentTimeMillis()
        MediaIndex.setManifest(fetched, prefs)

        val sameVersion = fetched.libraryVersion > 0L &&
            fetched.libraryVersion == prefs.localLibraryVersion

        if (storage == null || !storage.isAvailable()) {
            Log.w(Constants.LOG, "Media sync: no available storage, nothing downloaded")
            publish(SyncProgress(phase = SyncPhase.ERROR, message = "storage unavailable"))
            return
        }
        MediaIndex.freeBytes = storage.freeSpaceBytes()

        val resolution = MediaIndex.resolution()
        val queue = resolution.toDownload
            .mapNotNull { id -> fetched.byId(id) }
            .filter { it.id != currentlyPlayingId || MediaIndex.find(it.id) == null }

        if (queue.isEmpty()) {
            prefs.localLibraryVersion = fetched.libraryVersion
            prefs.lastSyncAt = System.currentTimeMillis()
            MediaIndex.lastSyncAt = prefs.lastSyncAt
            publish(SyncProgress(phase = SyncPhase.UP_TO_DATE))
            Log.i(
                Constants.LOG,
                "Media sync finished: up to date (libraryVersion=${fetched.libraryVersion}" +
                    if (sameVersion) ", unchanged)" else ")"
            )
            return
        }

        // Capacity check before touching the network. Never deletes anything to make room.
        val required = queue.sumOf { it.fileSize.coerceAtLeast(0L) }
        val free = MediaIndex.freeBytes
        if (free != null && required > 0L && free < required + Constants.STORAGE_HEADROOM_BYTES) {
            val message = "LOW STORAGE · required ${MediaScanner.formatBytes(required)} · " +
                "available ${MediaScanner.formatBytes(free)}"
            Log.w(Constants.LOG, "Media sync aborted: $message")
            publish(SyncProgress(phase = SyncPhase.ERROR, message = message))
            return
        }

        var completed = 0
        for ((position, video) in queue.withIndex()) {
            if (!scope.isActive) break
            publish(
                SyncProgress(
                    phase = SyncPhase.SYNCING,
                    currentIndex = position + 1,
                    totalItems = queue.size,
                    currentId = video.id,
                    currentTitle = video.title,
                    bytesDone = 0L,
                    bytesTotal = video.fileSize
                )
            )
            val ok = downloadOne(prefs, storage, video, position + 1, queue.size)
            if (ok) completed++
        }

        if (completed == queue.size) {
            prefs.localLibraryVersion = fetched.libraryVersion
        }
        prefs.lastSyncAt = System.currentTimeMillis()
        MediaIndex.lastSyncAt = prefs.lastSyncAt
        MediaIndex.freeBytes = storage.freeSpaceBytes()

        val failedNow = MediaIndex.failureList().size
        publish(
            if (failedNow > 0) {
                SyncProgress(phase = SyncPhase.ERROR, message = "$failedNow download(s) failed")
            } else {
                SyncProgress(phase = SyncPhase.UP_TO_DATE)
            }
        )
        Log.i(Constants.LOG, "Media sync finished: $completed/${queue.size} downloaded")
    }

    // ---------------------------------------------------------------- manifest

    fun fetchManifest(url: String): RemoteManifest? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) {
            Log.w(Constants.LOG, "Manifest URL must be https")
            return null
        }
        val connection = runCatching {
            (URL(url).openConnection() as? HttpsURLConnection)?.apply {
                connectTimeout = Constants.HTTP_CONNECT_TIMEOUT_MS
                readTimeout = Constants.HTTP_READ_TIMEOUT_MS
                requestMethod = "GET"
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Cache-Control", "no-cache")
            }
        }.getOrNull() ?: return null

        return try {
            val status = connection.responseCode
            if (status !in 200..299) {
                Log.w(Constants.LOG, "Manifest HTTP $status")
                return null
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            RemoteManifest.parse(body)?.also {
                Log.i(
                    Constants.LOG,
                    "Manifest fetched: libraryVersion=${it.libraryVersion}, " +
                        "${it.videos.size} entries"
                )
            }
        } catch (t: Throwable) {
            Log.w(Constants.LOG, "Manifest fetch failed: ${t.message}")
            null
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    // ---------------------------------------------------------------- one download

    /**
     * Downloads one video into "<name>.part", verifies it, then atomically promotes it.
     * The previous copy stays untouched and playable until the replacement is verified.
     */
    private suspend fun downloadOne(
        prefs: Prefs,
        storage: MediaStorage,
        video: RemoteVideo,
        position: Int,
        total: Int
    ): Boolean {
        if (!video.downloadable) {
            Log.i(Constants.LOG, "Skipping ${video.id}: no direct download URL (online only)")
            return false
        }

        val fileName = video.preferredFileName()
        MediaIndex.markDownloading(video.id)
        Log.i(Constants.LOG, "Download start ${video.id} -> $fileName from ${video.host}")

        var lastError = "unknown"
        var lastStatus: Int? = null
        var lastBytes = 0L

        for (attempt in 1..Constants.DOWNLOAD_ATTEMPTS) {
            if (!scope.isActive) break
            val part = storage.openPart(fileName)
            if (part == null) {
                lastError = "cannot create temporary file"
                break
            }

            val result = transfer(storage, part, video) { done, totalBytes ->
                publish(
                    SyncProgress(
                        phase = SyncPhase.SYNCING,
                        currentIndex = position,
                        totalItems = total,
                        currentId = video.id,
                        currentTitle = video.title,
                        bytesDone = done,
                        bytesTotal = if (totalBytes > 0) totalBytes else video.fileSize
                    )
                )
            }

            lastStatus = result.httpStatus
            lastBytes = result.bytesDownloaded
            if (!result.success) {
                lastError = result.error ?: "transfer failed"
                Log.w(Constants.LOG, "Download attempt $attempt failed for ${video.id}: $lastError")
                if (attempt < Constants.DOWNLOAD_ATTEMPTS) {
                    delay(2000L * attempt)
                    continue
                }
                break
            }

            // ---- verification, before anything is promoted ----
            val actualSize = storage.partSize(part)
            if (video.fileSize > 0L && actualSize != video.fileSize) {
                lastError = "size mismatch: expected ${video.fileSize}, got $actualSize"
                storage.deletePart(part)
                if (attempt < Constants.DOWNLOAD_ATTEMPTS) continue
                break
            }
            if (prefs.verifyDownloads && !video.sha256.isNullOrBlank()) {
                val digest = sha256Of(storage.openRead(part.uri))
                if (digest == null || !digest.equals(video.sha256, ignoreCase = true)) {
                    lastError = "checksum mismatch"
                    storage.deletePart(part)
                    if (attempt < Constants.DOWNLOAD_ATTEMPTS) continue
                    break
                }
            }

            // ---- promote only now; the old copy is replaced atomically ----
            if (video.id == currentlyPlayingId) {
                // The player is reading the current file. Keep the verified .part and let the
                // next sync pass promote it once playback has finished.
                Log.i(
                    Constants.LOG,
                    "Replacement for ${video.id} verified but deferred: file is playing"
                )
                MediaIndex.clearDownloading(video.id)
                return false
            }

            val finalUri = storage.promotePart(part)
            if (finalUri == null) {
                lastError = "could not replace existing file"
                if (attempt < Constants.DOWNLOAD_ATTEMPTS) continue
                break
            }

            MediaIndex.upsert(
                LocalVideo(
                    id = video.id,
                    uri = finalUri,
                    fileName = fileName,
                    sizeBytes = actualSize,
                    lastModified = System.currentTimeMillis(),
                    matchType = VideoIdMatcher.match(fileName)?.type ?: MatchType.FILENAME_ID,
                    version = video.version,
                    sha256 = video.sha256
                ),
                prefs
            )
            MediaIndex.clearFailure(video.id, prefs)
            Log.i(
                Constants.LOG,
                "Download complete ${video.id} (${MediaScanner.formatBytes(actualSize)})"
            )
            return true
        }

        MediaIndex.recordFailure(
            DownloadFailure(
                id = video.id,
                title = video.title,
                host = video.host,
                httpStatus = lastStatus,
                bytesDownloaded = lastBytes,
                retryCount = 1,
                lastError = lastError,
                lastAttemptAt = System.currentTimeMillis()
            ),
            prefs
        )
        return false
    }

    private data class TransferResult(
        val success: Boolean,
        val bytesDownloaded: Long,
        val httpStatus: Int?,
        val error: String?
    )

    /** HTTP(S) transfer with resume support via a Range request. */
    private fun transfer(
        storage: MediaStorage,
        part: PartFile,
        video: RemoteVideo,
        onProgress: (Long, Long) -> Unit
    ): TransferResult {
        val url = video.directDownloadUrl
            ?: return TransferResult(false, 0L, null, "no download url")

        var already = if (part.appendSupported) storage.partSize(part) else 0L
        if (video.fileSize in 1 until already) already = 0L    // stale/oversized part

        val connection = runCatching {
            (URL(url).openConnection() as? HttpsURLConnection)?.apply {
                connectTimeout = Constants.HTTP_CONNECT_TIMEOUT_MS
                readTimeout = Constants.HTTP_READ_TIMEOUT_MS
                requestMethod = "GET"
                instanceFollowRedirects = true
                setRequestProperty("Accept", "*/*")
                if (already > 0L) setRequestProperty("Range", "bytes=$already-")
            }
        }.getOrNull() ?: return TransferResult(false, 0L, null, "https connection refused")

        return try {
            val status = connection.responseCode
            val resuming = status == HttpURLConnection.HTTP_PARTIAL
            if (status !in 200..299) {
                return TransferResult(false, already, status, "HTTP $status")
            }
            if (!resuming) already = 0L   // server ignored the Range: start over

            val declared = connection.contentLengthLong.takeIf { it > 0L } ?: 0L
            val expectedTotal = if (video.fileSize > 0L) video.fileSize else already + declared

            var written = already
            val output = storage.openPartOutput(part, append = resuming && already > 0L)
                ?: return TransferResult(false, already, status, "cannot open temporary file")

            connection.inputStream.use { input ->
                output.use { out ->
                    val buffer = ByteArray(256 * 1024)
                    var lastPublish = 0L
                    while (true) {
                        if (!scope.isActive) {
                            return TransferResult(false, written, status, "cancelled")
                        }
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        written += read
                        val now = System.currentTimeMillis()
                        if (now - lastPublish > 500L) {
                            lastPublish = now
                            onProgress(written, expectedTotal)
                        }
                    }
                    out.flush()
                }
            }
            onProgress(written, expectedTotal)
            TransferResult(true, written, status, null)
        } catch (t: Throwable) {
            TransferResult(false, storage.partSize(part), null, t.message ?: t.javaClass.simpleName)
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private fun sha256Of(stream: InputStream?): String? {
        val input = stream ?: return null
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            input.use {
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val read = it.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        }.getOrNull()
    }

    /** Removes .part leftovers that no download is using (called after a sync pass). */
    fun cleanStaleParts(storage: MediaStorage) {
        runCatching {
            val active = MediaIndex.downloadingIds()
            storage.listPartFiles().forEach { file ->
                val base = file.name.removeSuffix(Constants.PART_SUFFIX)
                val id = VideoIdMatcher.match(base)?.id ?: VideoIdMatcher.nameKey(base)
                if (!active.contains(id)) {
                    Log.i(Constants.LOG, "Removing stale part file ${file.name}")
                    storage.delete(file.uri)
                }
            }
        }
    }
}
