package com.nogamt.showroom.media

import android.net.Uri
import com.nogamt.showroom.Constants
import org.json.JSONArray
import org.json.JSONObject

/**
 * One entry of the remote media manifest published by NOGA MT / Lovable.
 * The manifest is the download contract only - it is NOT a playlist and its order is ignored.
 */
data class RemoteVideo(
    val id: String,
    val title: String,
    val directDownloadUrl: String?,
    val fileName: String?,
    val fileSize: Long,
    val version: Long,
    val updatedAt: String?,
    val sha256: String?,
    val enabled: Boolean
) {
    /** True when this entry can be fetched for offline playback. */
    val downloadable: Boolean
        get() {
            val url = directDownloadUrl ?: return false
            val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
            if (!uri.scheme.equals("https", ignoreCase = true)) return false
            return !Constants.isBlockedDownloadHost(uri.host)
        }

    /** Host only - never the path or query, so signed URLs stay out of the staff UI. */
    val host: String
        get() = runCatching { Uri.parse(directDownloadUrl ?: "").host }.getOrNull().orEmpty()

    /**
     * File name to store this video under. The manifest name is honoured when it resolves
     * back to this id, otherwise a safe "<id>.mp4" is used so the scanner always matches it.
     */
    fun preferredFileName(): String {
        val candidate = fileName?.trim().orEmpty()
        if (candidate.isNotEmpty() && isSafeFileName(candidate)) {
            val ext = VideoIdMatcher.extensionOf(candidate)
            if (ext in Constants.VIDEO_EXTENSIONS) {
                val matched = VideoIdMatcher.match(candidate)?.id
                if (matched == id || VideoIdMatcher.nameKey(candidate) == id.lowercase()) {
                    return candidate
                }
            }
        }
        return "$id.mp4"
    }

    companion object {
        private val UNSAFE = Regex("[/\\\\:*?\"<>|\\u0000-\\u001f]")

        fun isSafeFileName(name: String): Boolean =
            name.isNotBlank() && name.length <= 180 &&
                !name.startsWith(".") && !UNSAFE.containsMatchIn(name) && !name.contains("..")

        fun fromJson(o: JSONObject): RemoteVideo? {
            val id = o.optString("id").trim()
            if (!VideoIdMatcher.isSafeRequestKey(id)) return null
            return RemoteVideo(
                id = id,
                title = o.optString("title", id),
                directDownloadUrl = o.optString("directDownloadUrl", "").ifBlank { null },
                fileName = o.optString("fileName", "").ifBlank { null },
                fileSize = o.optLong("fileSize", 0L),
                version = o.optLong("version", 1L),
                updatedAt = o.optString("updatedAt", "").ifBlank { null },
                sha256 = o.optString("sha256", "").ifBlank { null }?.lowercase(),
                enabled = o.optBoolean("enabled", true)
            )
        }
    }
}

data class RemoteManifest(
    val libraryVersion: Long,
    val videos: List<RemoteVideo>,
    val fetchedAt: Long
) {
    val enabledVideos: List<RemoteVideo> get() = videos.filter { it.enabled }

    fun byId(id: String): RemoteVideo? = videos.firstOrNull { it.id == id }

    fun toJson(): JSONObject {
        val array = JSONArray()
        videos.forEach { v ->
            array.put(
                JSONObject()
                    .put("id", v.id)
                    .put("title", v.title)
                    .put("directDownloadUrl", v.directDownloadUrl)
                    .put("fileName", v.fileName)
                    .put("fileSize", v.fileSize)
                    .put("version", v.version)
                    .put("updatedAt", v.updatedAt)
                    .put("sha256", v.sha256)
                    .put("enabled", v.enabled)
            )
        }
        return JSONObject()
            .put("libraryVersion", libraryVersion)
            .put("videos", array)
            .put("fetchedAt", fetchedAt)
    }

    companion object {
        fun parse(json: String, fetchedAt: Long = System.currentTimeMillis()): RemoteManifest? =
            runCatching {
                val root = JSONObject(json)
                val array = root.optJSONArray("videos") ?: JSONArray()
                val videos = ArrayList<RemoteVideo>(array.length())
                val seen = HashSet<String>()
                for (i in 0 until array.length()) {
                    val entry = array.optJSONObject(i) ?: continue
                    val video = RemoteVideo.fromJson(entry) ?: continue
                    if (seen.add(video.id)) videos.add(video)
                }
                RemoteManifest(
                    libraryVersion = root.optLong("libraryVersion", 0L),
                    videos = videos,
                    fetchedAt = root.optLong("fetchedAt", fetchedAt)
                )
            }.getOrNull()
    }
}
