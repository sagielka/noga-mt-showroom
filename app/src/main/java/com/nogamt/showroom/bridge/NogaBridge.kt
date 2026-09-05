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

    /**
     * True only when the id is indexed, the storage source is present, and the file is
     * really readable right now. A pulled USB drive turns this false within seconds.
     */
    @JavascriptInterface
    fun hasLocalVideo(videoId: String?): Boolean {
        if (!VideoIdMatcher.isSafeRequestKey(videoId)) return false
        val id = videoId!!
        val playable = MediaIndex.isPlayable(id)
        if (!playable) MediaIndex.noteMiss(id)
        return playable
    }

    @JavascriptInterface
    fun getLocalVideoInfo(videoId: String?): String? {
        if (!VideoIdMatcher.isSafeRequestKey(videoId)) return null
        val video = MediaIndex.find(videoId!!) ?: return null
        // Note: toJson() deliberately omits the URI and any filesystem path.
        return video.toJson()
            .put("available", MediaIndex.isPlayable(videoId))
            .put("state", MediaIndex.stateOf(video.id).name)
            .toString()
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
        if (!MediaIndex.isPlayable(id)) {
            MediaIndex.noteMiss(id)
            Log.i(Constants.LOG, "playLocalVideo($id) -> unavailable, web app should fall back")
            return false
        }
        Log.i(Constants.LOG, "playLocalVideo($id) -> accepted")
        // Optimistic: the real result is reported through the started/error events.
        main.post { host()?.bridgePlayLocalVideo(id) }
        return true
    }

    @JavascriptInterface
    fun stopLocalVideo() {
        Log.i(Constants.LOG, "stopLocalVideo()")
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
        Log.i(Constants.LOG, "refreshLocalMediaIndex()")
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
            Log.i(Constants.LOG, "reportPlaylist(${ids.size} ids)")
        }.onFailure { Log.w(Constants.LOG, "reportPlaylist ignored malformed payload") }
    }

    @JavascriptInterface
    fun log(message: String?) {
        Log.i(Constants.LOG, "[web] ${message?.take(500)}")
    }
}
