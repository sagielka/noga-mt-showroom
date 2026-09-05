package com.nogamt.showroom.media

import org.json.JSONArray
import org.json.JSONObject

/** Per-video state, exactly as reported to staff and to the web app diagnostics. */
enum class MediaState {
    LOCAL_READY,
    MISSING,
    DOWNLOADING,
    UPDATE_AVAILABLE,
    FAILED,
    ONLINE_ONLY,
    UNUSED
}

enum class SyncPhase { IDLE, CHECKING, SYNCING, UP_TO_DATE, ERROR, DISABLED }

/** Immutable snapshot of what the sync engine is doing right now. */
data class SyncProgress(
    val phase: SyncPhase = SyncPhase.IDLE,
    val currentIndex: Int = 0,
    val totalItems: Int = 0,
    val currentId: String? = null,
    val currentTitle: String? = null,
    val bytesDone: Long = 0L,
    val bytesTotal: Long = 0L,
    val message: String = ""
) {
    val active: Boolean get() = phase == SyncPhase.CHECKING || phase == SyncPhase.SYNCING

    fun describe(): String = when (phase) {
        SyncPhase.IDLE -> "IDLE"
        SyncPhase.CHECKING -> "CHECKING MANIFEST"
        SyncPhase.SYNCING ->
            "SYNCING MEDIA · video $currentIndex / $totalItems"
        SyncPhase.UP_TO_DATE -> "UP TO DATE"
        SyncPhase.ERROR -> "ERROR" + if (message.isBlank()) "" else " · $message"
        SyncPhase.DISABLED -> "AUTO-SYNC OFF"
    }
}

/** What went wrong for one video. Never contains query strings, tokens or credentials. */
data class DownloadFailure(
    val id: String,
    val title: String,
    val host: String,
    val httpStatus: Int?,
    val bytesDownloaded: Long,
    val retryCount: Int,
    val lastError: String,
    val lastAttemptAt: Long
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("host", host)
        .put("httpStatus", httpStatus ?: JSONObject.NULL)
        .put("bytesDownloaded", bytesDownloaded)
        .put("retryCount", retryCount)
        .put("lastError", lastError)
        .put("lastAttemptAt", lastAttemptAt)

    companion object {
        fun fromJson(o: JSONObject): DownloadFailure = DownloadFailure(
            id = o.optString("id"),
            title = o.optString("title"),
            host = o.optString("host"),
            httpStatus = if (o.isNull("httpStatus")) null else o.optInt("httpStatus"),
            bytesDownloaded = o.optLong("bytesDownloaded"),
            retryCount = o.optInt("retryCount"),
            lastError = o.optString("lastError"),
            lastAttemptAt = o.optLong("lastAttemptAt")
        )

        fun listToJson(items: Collection<DownloadFailure>): String {
            val array = JSONArray()
            items.forEach { array.put(it.toJson()) }
            return array.toString()
        }

        fun listFromJson(json: String?): List<DownloadFailure> {
            if (json.isNullOrBlank()) return emptyList()
            return runCatching {
                val array = JSONArray(json)
                (0 until array.length()).mapNotNull { i ->
                    array.optJSONObject(i)?.let { fromJson(it) }
                }
            }.getOrDefault(emptyList())
        }
    }
}
