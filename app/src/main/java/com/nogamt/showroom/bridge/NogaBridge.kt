package com.nogamt.showroom.bridge

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import com.nogamt.showroom.BuildConfig
import com.nogamt.showroom.Constants
import com.nogamt.showroom.media.MediaIndex
import com.nogamt.showroom.media.VideoIdMatcher
import org.json.JSONArray
import java.lang.ref.WeakReference

/**
 * The complete native surface exposed to the Lovable web application.
 *
 * Security notes:
 *  - no method accepts or returns a filesystem path or content URI;
 *  - ids are validated against a narrow character set before they are used;
 *  - the only reachable files are the ones the staff-selected folder scan indexed;
 *  - every UI/playback call hops to the main thread (JS bridge calls arrive on a
 *    dedicated WebView thread).
 */
class NogaBridge(host: BridgeHost) {

    private val hostRef = WeakReference(host)
    private val main = Handler(Looper.getMainLooper())

    private fun host(): BridgeHost? = hostRef.get()

    @JavascriptInterface
    fun isAndroidTV(): Boolean = true

    @JavascriptInterface
    fun getAppVersion(): String = BuildConfig.VERSION_NAME

    @JavascriptInterface
    fun hasLocalVideo(videoId: String?): Boolean {
        if (!VideoIdMatcher.isSafeRequestKey(videoId)) return false
        val found = MediaIndex.find(videoId!!) != null
        if (!found) MediaIndex.noteMiss(videoId)
        return found && MediaIndex.sourceAvailable
    }

    @JavascriptInterface
    fun getLocalVideoInfo(videoId: String?): String? {
        if (!VideoIdMatcher.isSafeRequestKey(videoId)) return null
        val video = MediaIndex.find(videoId!!) ?: return null
        // Note: toJson() deliberately omits the URI.
        return video.toJson().put("available", MediaIndex.sourceAvailable).toString()
    }

    @JavascriptInterface
    fun listLocalVideos(): String = MediaIndex.listJson().toString()

    @JavascriptInterface
    fun playLocalVideo(videoId: String?): Boolean {
        if (!VideoIdMatcher.isSafeRequestKey(videoId)) {
            Log.w(Constants.LOG, "playLocalVideo rejected an unsafe id")
            return false
        }
        val id = videoId!!
        if (MediaIndex.find(id) == null || !MediaIndex.sourceAvailable) {
            MediaIndex.noteMiss(id)
            return false
        }
        // Optimistic: the real result is reported through the started/error events.
        main.post { host()?.bridgePlayLocalVideo(id) }
        return true
    }

    @JavascriptInterface
    fun stopLocalVideo() {
        main.post { host()?.bridgeStopLocalVideo() }
    }

    @JavascriptInterface
    fun isLocalVideoPlaying(): Boolean = host()?.bridgeIsLocalPlaybackActive() ?: false

    @JavascriptInterface
    fun getMediaDiagnostics(): String = MediaIndex.diagnosticsJson()
        .put("appVersion", BuildConfig.VERSION_NAME)
        .toString()

    @JavascriptInterface
    fun openMediaSettings() {
        main.post { host()?.bridgeOpenMediaSettings() }
    }

    @JavascriptInterface
    fun refreshLocalMediaIndex() {
        main.post { host()?.bridgeRefreshMediaIndex() }
    }

    /** Optional. Lets the web app publish its playlist ids for the staff diagnostics screen. */
    @JavascriptInterface
    fun reportPlaylist(idsJson: String?) {
        if (idsJson.isNullOrBlank()) return
        runCatching {
            val array = JSONArray(idsJson)
            val ids = ArrayList<String>(array.length())
            for (i in 0 until array.length()) {
                val id = array.optString(i)
                if (VideoIdMatcher.isSafeRequestKey(id)) ids.add(id)
            }
            MediaIndex.reportPlaylist(ids)
        }.onFailure { Log.w(Constants.LOG, "reportPlaylist ignored malformed payload") }
    }

    @JavascriptInterface
    fun log(message: String?) {
        Log.i(Constants.LOG, "[web] ${message?.take(500)}")
    }
}
