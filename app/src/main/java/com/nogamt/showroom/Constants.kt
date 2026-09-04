package com.nogamt.showroom

/**
 * Central place for every tunable value in the shell.
 *
 * The Lovable web application at [DEFAULT_START_URL] owns the playlist, the UI and all
 * business logic. Nothing in this file describes content - only shell behaviour.
 */
object Constants {

    const val LOG = "NogaMT"

    /** TV entry point of the live Lovable application. */
    const val DEFAULT_START_URL = "https://noga-exhibit-buddy.lovable.app/tv"

    /** Hosts the WebView is allowed to navigate to on its own. */
    val ALLOWED_HOSTS = listOf(
        "noga-exhibit-buddy.lovable.app",
        "lovable.app",
        "noga.com",
        "noga-mt.com"
    )

    /** Name of the raw @JavascriptInterface object. The friendly `window.NogaAndroidTV`
     *  facade is installed on top of it by [com.nogamt.showroom.bridge.BridgeScript]. */
    const val JS_NATIVE_OBJECT = "NogaAndroidTVNative"

    /** Hold BACK this long to open the staff menu. */
    const val LONG_BACK_MS = 3000L

    /** Video extensions considered by the local media scanner. */
    val VIDEO_EXTENSIONS = setOf("mp4", "webm", "m4v")

    /** Recovery back-off, milliseconds. */
    const val RETRY_MIN_MS = 3_000L
    const val RETRY_MAX_MS = 60_000L

    /** Reload the web app when it has been backgrounded for longer than this. */
    const val STALE_BACKGROUND_MS = 30 * 60 * 1000L

    /** Safety limits for the SAF scanner. */
    const val SCAN_MAX_DEPTH = 6
    const val SCAN_MAX_FILES = 5000

    fun isAllowedHost(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return ALLOWED_HOSTS.any { h == it || h.endsWith(".$it") }
    }
}
