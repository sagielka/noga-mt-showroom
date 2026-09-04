package com.nogamt.showroom.media

import android.util.Log
import com.nogamt.showroom.Constants
import com.nogamt.showroom.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections

/**
 * The single in-memory index of locally available media.
 *
 * This is NOT a playlist. It answers exactly one question for the web app:
 * "do I have a local copy of this video id, and where is it?"
 */
object MediaIndex {

    private val byId = LinkedHashMap<String, LocalVideo>()
    private val byName = HashMap<String, LocalVideo>()

    private val unmatchedFiles = ArrayList<UnmatchedFile>()
    private val duplicateIds = ArrayList<DuplicateId>()

    /** Ids the web app asked for that we did not have. Powers "missing videos". */
    private val requestedMisses: MutableSet<String> =
        Collections.synchronizedSet(LinkedHashSet<String>())

    /** Optional: the web app may publish its playlist ids for diagnostics only. */
    private val reportedPlaylist: MutableList<String> =
        Collections.synchronizedList(ArrayList<String>())

    @Volatile var sourceLabel: String = ""
        private set
    @Volatile var sourceAvailable: Boolean = false
        private set
    @Volatile var scanning: Boolean = false
    @Volatile var lastScanAt: Long = 0L
        private set
    @Volatile var totalFilesSeen: Int = 0
        private set
    @Volatile var totalBytes: Long = 0L
        private set

    val matchedCount: Int get() = synchronized(this) { byId.size }
    val unmatchedCount: Int get() = synchronized(this) { unmatchedFiles.size }
    val duplicateCount: Int get() = synchronized(this) { duplicateIds.size }

    fun all(): List<LocalVideo> = synchronized(this) { byId.values.toList() }

    fun unmatched(): List<UnmatchedFile> = synchronized(this) { unmatchedFiles.toList() }

    fun duplicates(): List<DuplicateId> = synchronized(this) { duplicateIds.toList() }

    fun missing(): List<String> {
        val known = synchronized(this) { byId.keys.toSet() }
        val playlist = synchronized(reportedPlaylist) { reportedPlaylist.toList() }
        val misses = synchronized(requestedMisses) { requestedMisses.toList() }
        return (playlist.filterNot { known.contains(it) } + misses.filterNot { known.contains(it) })
            .distinct()
    }

    /** Lookup by video id, falling back to an exact file-name match. */
    fun find(key: String): LocalVideo? = synchronized(this) {
        byId[key] ?: byName[VideoIdMatcher.nameKey(key)]
    }

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
        sourceAvailable = available
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
                // still reachable by exact file name
                byName[VideoIdMatcher.nameKey(u.fileName)] = LocalVideo(
                    id = VideoIdMatcher.nameKey(u.fileName),
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
            sourceAvailable = result.sourceAvailable
        }
        lastScanAt = System.currentTimeMillis()
        requestedMisses.removeAll(synchronized(this) { byId.keys.toSet() })
        persist(prefs)
        Log.i(
            Constants.LOG,
            "Media index rebuilt: ${matchedCount} matched, ${unmatchedCount} unmatched, " +
                "${duplicateCount} duplicates, ${totalFilesSeen} files"
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
        prefs.mediaIndexJson = null
        prefs.lastScanAt = 0L
        lastScanAt = 0L
        Log.i(Constants.LOG, "Media index cleared (files untouched)")
    }

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
                    JSONObject()
                        .put("id", it.id).put("kept", it.kept).put("ignored", it.ignored)
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
                            o.optString("fileName"),
                            o.optString("uri"),
                            o.optLong("sizeBytes")
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
            Log.i(Constants.LOG, "Media index restored: ${matchedCount} videos")
        }.onFailure {
            Log.w(Constants.LOG, "Could not restore media index", it)
        }
    }

    /** JSON handed to JavaScript and to the staff Media screen. Contains no file paths. */
    fun diagnosticsJson(): JSONObject {
        val missingArray = JSONArray().apply { missing().forEach { put(it) } }
        val unmatchedArray = JSONArray().apply {
            unmatched().forEach { put(JSONObject().put("fileName", it.fileName).put("sizeBytes", it.sizeBytes)) }
        }
        val dupArray = JSONArray().apply {
            duplicates().forEach {
                put(JSONObject().put("id", it.id).put("kept", it.kept).put("ignored", it.ignored))
            }
        }
        return JSONObject()
            .put("sourceLabel", sourceLabel)
            .put("sourceAvailable", sourceAvailable)
            .put("scanning", scanning)
            .put("lastScanAt", lastScanAt)
            .put("filesDiscovered", totalFilesSeen)
            .put("matched", matchedCount)
            .put("unmatched", unmatchedCount)
            .put("duplicates", duplicateCount)
            .put("storageUsedBytes", totalBytes)
            .put("missing", missingArray)
            .put("unmatchedFiles", unmatchedArray)
            .put("duplicateIds", dupArray)
    }

    fun listJson(): JSONArray = JSONArray().apply { all().forEach { put(it.toJson()) } }
}
