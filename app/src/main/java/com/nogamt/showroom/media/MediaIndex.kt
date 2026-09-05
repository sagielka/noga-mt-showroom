package com.nogamt.showroom.media

import android.util.Log
import com.nogamt.showroom.Constants
import com.nogamt.showroom.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections

/**
 * The single in-memory index of locally available media, plus the cached remote manifest.
 *
 * This is NOT a playlist. It answers one question for the web app - "do I have a local copy of
 * this video id, and where is it?" - and it tells staff what the sync engine is doing.
 */
object MediaIndex {

    private val byId = LinkedHashMap<String, LocalVideo>()
    private val byName = HashMap<String, LocalVideo>()

    private val unmatchedFiles = ArrayList<UnmatchedFile>()
    private val duplicateIds = ArrayList<DuplicateId>()

    private val downloading: MutableSet<String> =
        Collections.synchronizedSet(LinkedHashSet<String>())
    private val failures = LinkedHashMap<String, DownloadFailure>()

    /** Ids the web app asked for that we did not have. Powers "missing videos". */
    private val requestedMisses: MutableSet<String> =
        Collections.synchronizedSet(LinkedHashSet<String>())

    /** Optional: the web app may publish its playlist ids for diagnostics only. */
    private val reportedPlaylist: MutableList<String> =
        Collections.synchronizedList(ArrayList<String>())

    /** Short-lived readability cache so bridge lookups stay fast. */
    private val readableCache = HashMap<String, Pair<Boolean, Long>>()

    @Volatile var manifest: RemoteManifest? = null
        private set
    @Volatile var sourceLabel: String = ""
        private set
    @Volatile var sourceAvailable: Boolean = false
        private set
    @Volatile var permissionValid: Boolean = false
    @Volatile var scanning: Boolean = false
    @Volatile var syncProgress: SyncProgress = SyncProgress()
    @Volatile var lastScanAt: Long = 0L
        private set
    @Volatile var lastSyncAt: Long = 0L
    @Volatile var totalFilesSeen: Int = 0
        private set
    @Volatile var totalBytes: Long = 0L
        private set
    @Volatile var freeBytes: Long? = null

    /** Set by MediaRepository so the index can probe a file without knowing the backend. */
    @Volatile var readabilityProbe: ((String) -> Boolean)? = null

    val matchedCount: Int get() = synchronized(this) { byId.size }
    val unmatchedCount: Int get() = synchronized(this) { unmatchedFiles.size }
    val duplicateCount: Int get() = synchronized(this) { duplicateIds.size }
    val remoteCount: Int get() = manifest?.enabledVideos?.size ?: 0

    fun all(): List<LocalVideo> = synchronized(this) { byId.values.toList() }
    fun unmatched(): List<UnmatchedFile> = synchronized(this) { unmatchedFiles.toList() }
    fun duplicates(): List<DuplicateId> = synchronized(this) { duplicateIds.toList() }
    fun failureList(): List<DownloadFailure> = synchronized(this) { failures.values.toList() }
    fun downloadingIds(): Set<String> = downloading.toSet()

    // ---------------------------------------------------------------- lookup

    /** Lookup by video id, falling back to an exact file-name match. */
    fun find(key: String): LocalVideo? = synchronized(this) {
        byId[key] ?: byName[VideoIdMatcher.nameKey(key)]
    }

    /**
     * The bridge answer: indexed AND the source is present AND the file is really readable.
     * The readability probe is cached briefly so repeated calls stay cheap.
     */
    fun isPlayable(key: String): Boolean {
        val video = find(key) ?: return false
        if (!sourceAvailable) return false
        val probe = readabilityProbe ?: return true
        val now = System.currentTimeMillis()
        synchronized(readableCache) {
            readableCache[video.uri]?.let { cached ->
                if (now - cached.second < Constants.READABILITY_CACHE_MS) return cached.first
            }
        }
        val readable = runCatching { probe(video.uri) }.getOrDefault(false)
        synchronized(readableCache) { readableCache[video.uri] = readable to now }
        return readable
    }

    fun invalidateReadability() = synchronized(readableCache) { readableCache.clear() }

    fun noteMiss(key: String) {
        if (find(key) == null) requestedMisses.add(key)
    }

    fun reportPlaylist(ids: List<String>) {
        synchronized(reportedPlaylist) {
            reportedPlaylist.clear()
            reportedPlaylist.addAll(ids.distinct())
        }
    }

    fun setSourceAvailable(available: Boolean) {
        if (sourceAvailable != available) invalidateReadability()
        sourceAvailable = available
    }

    // ---------------------------------------------------------------- state

    fun resolution(): MediaStateResolver.Resolution = MediaStateResolver.resolve(
        remote = manifest?.videos ?: emptyList(),
        local = synchronized(this) { LinkedHashMap(byId) },
        sourceAvailable = sourceAvailable,
        downloading = downloadingIds(),
        failed = synchronized(this) { failures.keys.toSet() }
    )

    fun stateOf(id: String): MediaState =
        resolution().states[id] ?: if (find(id) != null) MediaState.UNUSED else MediaState.MISSING

    /** Ids in the manifest with no usable local copy, plus anything the web app asked for. */
    fun missing(): List<String> {
        val res = resolution()
        val fromManifest = res.states.filterValues {
            it == MediaState.MISSING || it == MediaState.FAILED
        }.keys
        val known = synchronized(this) { byId.keys.toSet() }
        val asked = synchronized(requestedMisses) { requestedMisses.toList() }
        val playlist = synchronized(reportedPlaylist) { reportedPlaylist.toList() }
        return (fromManifest +
            playlist.filterNot { known.contains(it) } +
            asked.filterNot { known.contains(it) }).distinct()
    }

    /** Local files that the remote library no longer lists. Never auto-deleted. */
    fun unusedVideos(): List<LocalVideo> {
        val states = resolution().states
        return all().filter { states[it.id] == MediaState.UNUSED }
    }

    // ---------------------------------------------------------------- mutation

    fun setManifest(newManifest: RemoteManifest?, prefs: Prefs) {
        manifest = newManifest
        if (newManifest != null) {
            prefs.manifestJson = newManifest.toJson().toString()
        }
    }

    fun markDownloading(id: String) {
        downloading.add(id)
        synchronized(this) { failures.remove(id) }
    }

    fun clearDownloading(id: String) {
        downloading.remove(id)
    }

    fun recordFailure(failure: DownloadFailure, prefs: Prefs) {
        synchronized(this) {
            val previous = failures[failure.id]
            failures[failure.id] = failure.copy(
                retryCount = (previous?.retryCount ?: 0) + failure.retryCount
            )
            prefs.failuresJson = DownloadFailure.listToJson(failures.values)
        }
        downloading.remove(failure.id)
        Log.w(
            Constants.LOG,
            "Download failed: ${failure.id} host=${failure.host} " +
                "status=${failure.httpStatus} bytes=${failure.bytesDownloaded} " +
                "error=${failure.lastError}"
        )
    }

    fun clearFailure(id: String, prefs: Prefs) {
        synchronized(this) {
            failures.remove(id)
            prefs.failuresJson = DownloadFailure.listToJson(failures.values)
        }
    }

    fun clearAllFailures(prefs: Prefs) {
        synchronized(this) {
            failures.clear()
            prefs.failuresJson = null
        }
    }

    /** Adds or replaces a single entry after a successful download - no full rescan needed. */
    fun upsert(video: LocalVideo, prefs: Prefs) {
        synchronized(this) {
            byId[video.id] = video
            byName[VideoIdMatcher.nameKey(video.fileName)] = video
            failures.remove(video.id)
        }
        downloading.remove(video.id)
        requestedMisses.remove(video.id)
        invalidateReadability()
        persist(prefs)
        Log.i(Constants.LOG, "Indexed ${video.id} (${video.fileName})")
    }

    fun removeById(id: String, prefs: Prefs) {
        synchronized(this) {
            val removed = byId.remove(id)
            if (removed != null) byName.remove(VideoIdMatcher.nameKey(removed.fileName))
        }
        invalidateReadability()
        persist(prefs)
    }

    fun replace(result: MediaScanner.ScanResult, prefs: Prefs) {
        synchronized(this) {
            byId.clear(); byName.clear()
            unmatchedFiles.clear(); duplicateIds.clear()

            result.videos.forEach { v ->
                byId[v.id] = v
                byName[VideoIdMatcher.nameKey(v.fileName)] = v
            }
            result.unmatched.forEach { u ->
                unmatchedFiles.add(u)
                // Still reachable by exact file name (rule 4: stable filename identity).
                val key = VideoIdMatcher.nameKey(u.fileName)
                byName[key] = LocalVideo(
                    id = key,
                    uri = u.uri,
                    fileName = u.fileName,
                    sizeBytes = u.sizeBytes,
                    lastModified = 0L,
                    matchType = MatchType.UNMATCHED
                )
            }
            duplicateIds.addAll(result.duplicates)
            totalFilesSeen = result.totalFiles
            totalBytes = result.totalBytes
            sourceLabel = result.sourceLabel
        }
        setSourceAvailable(result.sourceAvailable)
        lastScanAt = System.currentTimeMillis()
        requestedMisses.removeAll(synchronized(this) { byId.keys.toSet() })
        persist(prefs)
        Log.i(
            Constants.LOG,
            "Media index rebuilt: $matchedCount matched, $unmatchedCount unmatched, " +
                "$duplicateCount duplicates, $totalFilesSeen files"
        )
    }

    /** Clears the in-app index only. Never touches customer media files. */
    fun clear(prefs: Prefs) {
        synchronized(this) {
            byId.clear(); byName.clear()
            unmatchedFiles.clear(); duplicateIds.clear()
            totalFilesSeen = 0; totalBytes = 0L
        }
        requestedMisses.clear()
        invalidateReadability()
        prefs.mediaIndexJson = null
        prefs.lastScanAt = 0L
        lastScanAt = 0L
        Log.i(Constants.LOG, "Media index cleared (files untouched)")
    }

    // ---------------------------------------------------------------- persistence

    fun persist(prefs: Prefs) {
        val root = JSONObject()
        val videos = JSONArray()
        val unmatched = JSONArray()
        val dupes = JSONArray()
        synchronized(this) {
            byId.values.forEach { videos.put(it.toStorageJson()) }
            unmatchedFiles.forEach {
                unmatched.put(
                    JSONObject()
                        .put("fileName", it.fileName)
                        .put("uri", it.uri)
                        .put("sizeBytes", it.sizeBytes)
                )
            }
            duplicateIds.forEach {
                dupes.put(
                    JSONObject().put("id", it.id).put("kept", it.kept).put("ignored", it.ignored)
                )
            }
            root.put("videos", videos)
            root.put("unmatched", unmatched)
            root.put("duplicates", dupes)
            root.put("totalFiles", totalFilesSeen)
            root.put("totalBytes", totalBytes)
            root.put("sourceLabel", sourceLabel)
        }
        prefs.mediaIndexJson = root.toString()
        prefs.lastScanAt = lastScanAt
    }

    fun restore(prefs: Prefs) {
        manifest = prefs.manifestJson?.let { RemoteManifest.parse(it) }
        lastSyncAt = prefs.lastSyncAt
        synchronized(this) {
            failures.clear()
            DownloadFailure.listFromJson(prefs.failuresJson).forEach { failures[it.id] = it }
        }

        val json = prefs.mediaIndexJson ?: return
        runCatching {
            val root = JSONObject(json)
            synchronized(this) {
                byId.clear(); byName.clear(); unmatchedFiles.clear(); duplicateIds.clear()
                val videos = root.optJSONArray("videos") ?: JSONArray()
                for (i in 0 until videos.length()) {
                    LocalVideo.fromJson(videos.getJSONObject(i))?.let { v ->
                        byId[v.id] = v
                        byName[VideoIdMatcher.nameKey(v.fileName)] = v
                    }
                }
                val un = root.optJSONArray("unmatched") ?: JSONArray()
                for (i in 0 until un.length()) {
                    val o = un.getJSONObject(i)
                    unmatchedFiles.add(
                        UnmatchedFile(
                            o.optString("fileName"), o.optString("uri"), o.optLong("sizeBytes")
                        )
                    )
                }
                val dup = root.optJSONArray("duplicates") ?: JSONArray()
                for (i in 0 until dup.length()) {
                    val o = dup.getJSONObject(i)
                    duplicateIds.add(
                        DuplicateId(o.optString("id"), o.optString("kept"), o.optString("ignored"))
                    )
                }
                totalFilesSeen = root.optInt("totalFiles", byId.size)
                totalBytes = root.optLong("totalBytes", 0L)
                sourceLabel = root.optString("sourceLabel", "")
            }
            lastScanAt = prefs.lastScanAt
            Log.i(Constants.LOG, "Media index restored: $matchedCount videos")
        }.onFailure {
            Log.w(Constants.LOG, "Could not restore media index", it)
        }
    }

    // ---------------------------------------------------------------- diagnostics

    /** JSON for JavaScript and the staff screens. Contains no file paths and no URLs. */
    fun diagnosticsJson(): JSONObject {
        val res = resolution()
        val missingArray = JSONArray().apply { missing().forEach { put(it) } }
        val unmatchedArray = JSONArray().apply {
            unmatched().forEach {
                put(JSONObject().put("fileName", it.fileName).put("sizeBytes", it.sizeBytes))
            }
        }
        val dupArray = JSONArray().apply {
            duplicates().forEach {
                put(JSONObject().put("id", it.id).put("kept", it.kept).put("ignored", it.ignored))
            }
        }
        val failedArray = JSONArray().apply { failureList().forEach { put(it.toJson()) } }
        val statesObject = JSONObject().apply {
            res.states.forEach { entry -> put(entry.key, entry.value.name) }
        }
        val progress = syncProgress

        return JSONObject()
            .put("sourceLabel", sourceLabel)
            .put("sourceAvailable", sourceAvailable)
            .put("permissionValid", permissionValid)
            .put("scanning", scanning)
            .put("libraryVersion", manifest?.libraryVersion ?: -1L)
            .put("remoteLibrary", remoteCount)
            .put("filesDiscovered", totalFilesSeen)
            .put("matched", matchedCount)
            .put("localReady", res.count(MediaState.LOCAL_READY))
            .put("missingCount", res.count(MediaState.MISSING))
            .put("updateAvailable", res.count(MediaState.UPDATE_AVAILABLE))
            .put("downloading", res.count(MediaState.DOWNLOADING))
            .put("failed", res.count(MediaState.FAILED))
            .put("onlineOnly", res.count(MediaState.ONLINE_ONLY))
            .put("unused", res.count(MediaState.UNUSED))
            .put("unmatched", unmatchedCount)
            .put("duplicates", duplicateCount)
            .put("storageUsedBytes", totalBytes)
            .put("storageFreeBytes", freeBytes ?: JSONObject.NULL)
            .put("lastScanAt", lastScanAt)
            .put("lastSyncAt", lastSyncAt)
            .put("syncPhase", progress.phase.name)
            .put("syncStatus", progress.describe())
            .put("states", statesObject)
            .put("missing", missingArray)
            .put("unmatchedFiles", unmatchedArray)
            .put("duplicateIds", dupArray)
            .put("failedDownloads", failedArray)
    }

    fun listJson(): JSONArray = JSONArray().apply {
        val states = resolution().states
        all().forEach { video ->
            put(video.toJson().put("state", (states[video.id] ?: MediaState.LOCAL_READY).name))
        }
    }
}
