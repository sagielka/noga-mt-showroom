package com.nogamt.showroom.media

import org.json.JSONObject

enum class MatchType { BRACKET_ID, SUFFIX_ID, FILENAME_ID, UNMATCHED }

/** One playable local file, keyed by the video id the Lovable playlist uses. */
data class LocalVideo(
    val id: String,
    val uri: String,
    val fileName: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val matchType: MatchType,
    /** Manifest version this copy came from, 0 when it was copied in manually. */
    val version: Long = 0L,
    val sha256: String? = null
) {
    /** Sent to JavaScript - deliberately carries no URI and no path. */
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("fileName", fileName)
        .put("sizeBytes", sizeBytes)
        .put("lastModified", lastModified)
        .put("matchType", matchType.name)
        .put("version", version)

    /** Includes the storage URI. Persisted locally, never exposed to the web app. */
    fun toStorageJson(): JSONObject = toJson()
        .put("uri", uri)
        .put("sha256", sha256 ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject): LocalVideo? {
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
            val uri = o.optString("uri").takeIf { it.isNotBlank() } ?: return null
            return LocalVideo(
                id = id,
                uri = uri,
                fileName = o.optString("fileName", id),
                sizeBytes = o.optLong("sizeBytes", 0L),
                lastModified = o.optLong("lastModified", 0L),
                matchType = runCatching {
                    MatchType.valueOf(o.optString("matchType", MatchType.FILENAME_ID.name))
                }.getOrDefault(MatchType.FILENAME_ID),
                version = o.optLong("version", 0L),
                sha256 = if (o.isNull("sha256")) null else o.optString("sha256").ifBlank { null }
            )
        }
    }
}

data class UnmatchedFile(val fileName: String, val uri: String, val sizeBytes: Long)

data class DuplicateId(val id: String, val kept: String, val ignored: String)
