package com.nogamt.showroom

/**
 * Central place for every tunable value in the shell.
 *
 * The Lovable web application at [DEFAULT_START_URL] owns the playlist, the UI, presentation
 * timing and all business logic. Nothing in this file describes content - only shell behaviour.
 */
object Constants {

    const val LOG = "NogaMT"

    /** TV entry point of the live Lovable application. */
    const val DEFAULT_START_URL = "https://noga-exhibit-buddy.lovable.app/tv"

    /**
     * The live NOGA MT / Lovable media manifest endpoint. Staff never have to type this:
     * it is the built-in default and is used automatically on a fresh install.
     * It stays editable in staff settings only as an escape hatch.
     */
    const val DEFAULT_MANIFEST_URL =
        "https://noga-exhibit-buddy.lovable.app/api/public/android-media-manifest"

    /**
     * Pre-release default that was never a real endpoint. Any device that stored it is
     * silently migrated back to DEFAULT_MANIFEST_URL - see Prefs.manifestUrl.
     */
    const val LEGACY_MANIFEST_URL = "https://noga-exhibit-buddy.lovable.app/media-manifest.json"

    /** Hosts the WebView is allowed to navigate to on its own. */
    val ALLOWED_HOSTS = listOf(
        "noga-exhibit-buddy.lovable.app",
        "lovable.app",
        "noga.com",
        "noga-mt.com"
    )

    /** Hosts that must never be used as an offline download source. */
    val BLOCKED_DOWNLOAD_HOSTS = listOf(
        "youtube.com", "youtu.be", "ytimg.com", "googlevideo.com",
        "vimeo.com", "vimeocdn.com", "player.vimeo.com"
    )

    /** Name of the raw @JavascriptInterface object. */
    const val JS_NATIVE_OBJECT = "NogaAndroidTVNative"

    /** Hold BACK this long to open the staff menu. */
    const val LONG_BACK_MS = 3000L

    /** Video extensions considered by the local media scanner. */
    val VIDEO_EXTENSIONS = setOf("mp4", "webm", "m4v")

    /** Suffix used for in-flight downloads. Never indexed, never played. */
    const val PART_SUFFIX = ".part"

    /** Folder created inside the chosen storage location. */
    const val MEDIA_DIR_NAME = "NOGA-MT"
    const val VIDEO_DIR_NAME = "videos"

    /** Web recovery back-off, milliseconds. */
    const val RETRY_MIN_MS = 3_000L
    const val RETRY_MAX_MS = 60_000L

    /** Reload the web app when it has been backgrounded for longer than this. */
    const val STALE_BACKGROUND_MS = 30 * 60 * 1000L

    /** Safety limits for the storage scanner. */
    const val SCAN_MAX_DEPTH = 6
    const val SCAN_MAX_FILES = 5000

    // ---------------- media sync ----------------

    /** Periodic manifest check while the app runs. Deliberately unhurried. */
    const val SYNC_INTERVAL_MS = 30 * 60 * 1000L

    /** Never re-fetch the manifest more often than this, whatever triggers a sync. */
    const val SYNC_MIN_GAP_MS = 60_000L

    /** Per-download network timeouts. */
    const val HTTP_CONNECT_TIMEOUT_MS = 20_000
    const val HTTP_READ_TIMEOUT_MS = 60_000

    /** Attempts per file within one sync pass before it is marked FAILED. */
    const val DOWNLOAD_ATTEMPTS = 3

    /** Headroom kept free on the media volume, on top of the download size. */
    const val STORAGE_HEADROOM_BYTES = 300L * 1024L * 1024L

    /** Staff-only low-storage warning threshold. */
    const val LOW_STORAGE_BYTES = 2L * 1024L * 1024L * 1024L

    /** Re-verify a file's readability at most this often per id (fast bridge lookups). */
    const val READABILITY_CACHE_MS = 5_000L

    fun isAllowedHost(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return ALLOWED_HOSTS.any { h == it || h.endsWith(".$it") }
    }

    /** Offline downloads may never come from a streaming platform. */
    fun isBlockedDownloadHost(host: String?): Boolean {
        val h = host?.lowercase() ?: return true
        return BLOCKED_DOWNLOAD_HOSTS.any { h == it || h.endsWith(".$it") }
    }
}
