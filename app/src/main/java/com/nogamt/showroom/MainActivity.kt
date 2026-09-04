package com.nogamt.showroom

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.nogamt.showroom.bridge.BridgeHost
import com.nogamt.showroom.bridge.BridgeScript
import com.nogamt.showroom.bridge.NogaBridge
import com.nogamt.showroom.media.LocalPlayerController
import com.nogamt.showroom.media.MediaIndex
import com.nogamt.showroom.media.MediaRepository
import com.nogamt.showroom.net.NetworkMonitor
import com.nogamt.showroom.staff.MediaManagerActivity
import com.nogamt.showroom.staff.StaffSettingsActivity
import com.nogamt.showroom.web.ShowroomWebChromeClient
import com.nogamt.showroom.web.ShowroomWebViewClient
import com.nogamt.showroom.web.WebViewFactory
import org.json.JSONObject
import kotlin.math.min

/**
 * The showroom shell.
 *
 * Responsibilities: fullscreen kiosk window, WebView lifecycle, remote handling, staff menu,
 * crash/network recovery and native local playback. It owns no content and no playlist -
 * the Lovable web app decides everything the visitor sees.
 */
@UnstableApi
class MainActivity : AppCompatActivity(),
    BridgeHost,
    ShowroomWebViewClient.Callbacks,
    ShowroomWebChromeClient.Callbacks,
    LocalPlayerController.Events {

    private lateinit var prefs: Prefs
    private lateinit var root: FrameLayout
    private lateinit var webContainer: FrameLayout
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var offlineOverlay: View
    private lateinit var offlineStatus: TextView
    private lateinit var offlineDetail: TextView

    private var webView: WebView? = null
    private var chromeClient: ShowroomWebChromeClient? = null
    private var bridge: NogaBridge? = null
    private var player: LocalPlayerController? = null
    private lateinit var networkMonitor: NetworkMonitor

    private val handler = Handler(Looper.getMainLooper())

    private var contentReady = false
    private var retryAttempt = 0
    private var offlineVisible = false
    private var backgroundedAt = 0L
    private var pendingReload = false
    private var htmlFullscreenCallback: WebChromeClient.CustomViewCallback? = null

    private var backLongPressFired = false
    private var staffMenu: AlertDialog? = null

    private val retryRunnable = Runnable { attemptRecoveryLoad() }

    private val longBackRunnable = Runnable {
        backLongPressFired = true
        showStaffMenu()
    }

    private val staffLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            applyWindowPreferences()
            when (result.data?.getStringExtra(EXTRA_STAFF_ACTION)) {
                ACTION_RELOAD -> reloadWebApp(force = false)
                ACTION_FORCE_REFRESH -> reloadWebApp(force = true)
                else -> Unit
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(this)
        setContentView(R.layout.activity_main)

        root = findViewById(R.id.root)
        webContainer = findViewById(R.id.web_container)
        fullscreenContainer = findViewById(R.id.fullscreen_container)
        playerView = findViewById(R.id.player_view)
        offlineOverlay = findViewById(R.id.offline_overlay)
        offlineStatus = findViewById(R.id.offline_status)
        offlineDetail = findViewById(R.id.offline_detail)

        player = LocalPlayerController(this, playerView, this)

        networkMonitor = NetworkMonitor(this)
        networkMonitor.start { online -> handler.post { onConnectivityChanged(online) } }

        applyWindowPreferences()
        buildWebView()
        loadStartUrl(force = false)

        // Confirm the media source is still reachable; this also rescans it when it is,
        // so the cached index picks up any videos copied on while the app was closed.
        MediaRepository.verifySourceAvailability(this)

        if (intent?.getBooleanExtra(EXTRA_FROM_BOOT, false) == true) {
            Log.i(Constants.LOG, "Launched by BootReceiver")
        }
    }

    // ---------------------------------------------------------------- window / kiosk

    private fun applyWindowPreferences() {
        if (prefs.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        enterImmersive()
    }

    private fun enterImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    // ---------------------------------------------------------------- webview

    private fun buildWebView() {
        destroyWebView()

        val wv = WebViewFactory.create(this)
        val bridgeImpl = NogaBridge(this)
        bridge = bridgeImpl

        wv.addJavascriptInterface(bridgeImpl, Constants.JS_NATIVE_OBJECT)
        wv.webViewClient = ShowroomWebViewClient(this)
        chromeClient = ShowroomWebChromeClient(this).also { wv.webChromeClient = it }

        // Best case: the facade exists before any page script runs.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            runCatching {
                WebViewCompat.addDocumentStartJavaScript(
                    wv,
                    BridgeScript.SOURCE,
                    documentStartOrigins()
                )
            }.onFailure {
                Log.w(Constants.LOG, "addDocumentStartJavaScript unavailable: ${it.message}")
            }
        }

        webContainer.addView(wv)
        webView = wv
        wv.requestFocus()
    }

    private fun documentStartOrigins(): Set<String> =
        Constants.ALLOWED_HOSTS.flatMap { listOf("https://$it", "https://*.$it") }.toSet()

    private fun destroyWebView() {
        val wv = webView ?: return
        webView = null
        runCatching {
            wv.stopLoading()
            wv.webChromeClient = null
            wv.removeJavascriptInterface(Constants.JS_NATIVE_OBJECT)
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.removeAllViews()
            wv.destroy()
        }.onFailure { Log.w(Constants.LOG, "WebView teardown issue", it) }
        bridge = null
        chromeClient = null
    }

    private fun loadStartUrl(force: Boolean) {
        val url = prefs.startUrl
        contentReady = false
        val wv = webView ?: run { buildWebView(); webView } ?: return
        if (force) WebViewFactory.clearCaches(wv)
        Log.i(Constants.LOG, "Loading $url (force=$force)")
        wv.loadUrl(url)
    }

    /** Safe reload: never interrupts a running local video. */
    fun reloadWebApp(force: Boolean) {
        if (player?.isActive == true) {
            Log.i(Constants.LOG, "Reload deferred until local playback ends")
            pendingReload = true
            return
        }
        pendingReload = false
        retryAttempt = 0
        loadStartUrl(force)
    }

    // ---------------------------------------------------------------- recovery

    private fun showOffline(detail: String) {
        offlineVisible = true
        offlineOverlay.visibility = View.VISIBLE
        offlineStatus.setText(R.string.offline_message)
        offlineDetail.text = detail
    }

    private fun hideOffline() {
        if (!offlineVisible) return
        offlineVisible = false
        offlineOverlay.visibility = View.GONE
        offlineDetail.text = ""
    }

    private fun scheduleRetry(reason: String) {
        if (!prefs.autoRecovery) {
            Log.w(Constants.LOG, "Auto recovery disabled, not retrying ($reason)")
            return
        }
        handler.removeCallbacks(retryRunnable)
        val delay = min(
            Constants.RETRY_MIN_MS shl min(retryAttempt, 5),
            Constants.RETRY_MAX_MS
        )
        retryAttempt++
        Log.i(Constants.LOG, "Retry #$retryAttempt in ${delay}ms ($reason)")
        if (offlineVisible) {
            offlineDetail.text = "$reason · retry in ${delay / 1000}s"
        }
        handler.postDelayed(retryRunnable, delay)
    }

    private fun attemptRecoveryLoad() {
        if (isFinishing || isDestroyed) return
        if (player?.isActive == true) {
            // Never yank the screen out from under a playing local video.
            handler.postDelayed(retryRunnable, Constants.RETRY_MIN_MS)
            return
        }
        Log.i(Constants.LOG, "Recovery load attempt $retryAttempt")
        loadStartUrl(force = retryAttempt >= 3)
    }

    private fun onConnectivityChanged(online: Boolean) {
        if (online && (offlineVisible || !contentReady)) {
            handler.removeCallbacks(retryRunnable)
            handler.postDelayed(retryRunnable, 1200L)
        }
        if (!online) {
            Log.w(Constants.LOG, "Offline - keeping current UI, local media still playable")
        }
    }

    // ---------------------------------------------------------------- WebViewClient callbacks

    override fun onPageLoadStarted(url: String) {
        Log.i(Constants.LOG, "Page start: $url")
    }

    override fun onPageLoadFinished(url: String) {
        Log.i(Constants.LOG, "Page ready: $url")
        contentReady = true
        retryAttempt = 0
        handler.removeCallbacks(retryRunnable)
        hideOffline()
        webView?.requestFocus()
    }

    override fun onMainFrameFailure(reason: String) {
        if (contentReady) {
            // Requirement: a single failed request must not replace a working UI.
            Log.w(Constants.LOG, "Main frame error while content is live, ignoring: $reason")
            return
        }
        showOffline(reason)
        scheduleRetry(reason)
    }

    override fun onRendererGone(didCrash: Boolean): Boolean {
        contentReady = false
        showOffline(if (didCrash) "Renderer crashed" else "Renderer reclaimed")
        buildWebView()
        handler.postDelayed({ loadStartUrl(force = false) }, 1500L)
        return true
    }

    override fun onExternalLinkBlocked(url: String) {
        Log.i(Constants.LOG, "External link blocked: $url")
        toast("External links are disabled in showroom mode")
    }

    // ---------------------------------------------------------------- WebChromeClient callbacks

    override fun onEnterHtmlFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        htmlFullscreenCallback = callback
        fullscreenContainer.removeAllViews()
        fullscreenContainer.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        fullscreenContainer.visibility = View.VISIBLE
        webContainer.visibility = View.INVISIBLE
        view.requestFocus()
        enterImmersive()
    }

    override fun onExitHtmlFullscreen() {
        fullscreenContainer.removeAllViews()
        fullscreenContainer.visibility = View.GONE
        webContainer.visibility = View.VISIBLE
        htmlFullscreenCallback = null
        webView?.requestFocus()
        enterImmersive()
    }

    override fun onProgress(progress: Int) = Unit

    // ---------------------------------------------------------------- bridge host

    override fun bridgePlayLocalVideo(id: String): Boolean {
        val video = MediaIndex.find(id) ?: run {
            emitWebEvent(EVENT_ERROR, id, "not_indexed")
            return false
        }
        webContainer.visibility = View.VISIBLE
        return player?.play(video) ?: false
    }

    override fun bridgeStopLocalVideo() {
        player?.stop()
    }

    override fun bridgeOpenMediaSettings() {
        openMediaManager()
    }

    override fun bridgeRefreshMediaIndex() {
        MediaRepository.rescan(this) {
            toast("Media index refreshed: ${MediaIndex.matchedCount} videos")
        }
    }

    override fun bridgeIsLocalPlaybackActive(): Boolean = player?.isActive == true

    // ---------------------------------------------------------------- player events -> web

    override fun onLocalVideoStarted(id: String) = emitWebEvent(EVENT_STARTED, id, null)

    override fun onLocalVideoEnded(id: String) {
        emitWebEvent(EVENT_ENDED, id, null)
        afterPlaybackFinished()
    }

    override fun onLocalVideoStopped(id: String) {
        emitWebEvent(EVENT_STOPPED, id, null)
        afterPlaybackFinished()
    }

    override fun onLocalVideoError(id: String, message: String) {
        emitWebEvent(EVENT_ERROR, id, message)
        afterPlaybackFinished()
        // A dead file usually means the USB was pulled - re-check so hasLocalVideo()
        // starts answering false and the web app falls back online.
        MediaRepository.verifySourceAvailability(this)
    }

    private fun afterPlaybackFinished() {
        if (pendingReload) {
            pendingReload = false
            handler.postDelayed({ reloadWebApp(force = false) }, 400L)
        }
    }

    private fun emitWebEvent(name: String, id: String, message: String?) {
        val detail = JSONObject().put("id", id)
        if (message != null) detail.put("error", message)
        val js = "window.dispatchEvent(new CustomEvent(${JSONObject.quote(name)}," +
            "{ detail: $detail }));"
        runCatching { webView?.evaluateJavascript(js, null) }
            .onFailure { Log.w(Constants.LOG, "Could not deliver $name", it) }
        Log.i(Constants.LOG, "-> web: $name $id ${message ?: ""}")
    }

    // ---------------------------------------------------------------- remote control

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (event.repeatCount == 0) {
                        backLongPressFired = false
                        handler.removeCallbacks(longBackRunnable)
                        handler.postDelayed(longBackRunnable, Constants.LONG_BACK_MS)
                    }
                    return true
                }
                KeyEvent.ACTION_UP -> {
                    handler.removeCallbacks(longBackRunnable)
                    if (!backLongPressFired) handleShortBack()
                    backLongPressFired = false
                    return true
                }
            }
            return true
        }

        if (event.action == KeyEvent.ACTION_DOWN && player?.isActive == true) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> {
                    player?.togglePlayPause()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { player?.resume(); return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { player?.pause(); return true }
                KeyEvent.KEYCODE_MEDIA_STOP,
                KeyEvent.KEYCODE_MEDIA_NEXT -> { player?.stop(); return true }
            }
        }

        return super.dispatchKeyEvent(event)
    }

    private fun handleShortBack() {
        // Never exit to the Android launcher by accident.
        when {
            fullscreenContainer.visibility == View.VISIBLE -> {
                chromeClient?.onHideCustomView() ?: onExitHtmlFullscreen()
            }
            player?.isActive == true -> player?.stop()
            webView?.canGoBack() == true -> webView?.goBack()
            else -> Log.i(Constants.LOG, "BACK ignored (kiosk mode)")
        }
    }

    // ---------------------------------------------------------------- staff menu

    private fun showStaffMenu() {
        if (staffMenu?.isShowing == true) return
        val options = arrayOf(
            getString(R.string.staff_return),
            getString(R.string.staff_reload),
            getString(R.string.staff_settings),
            getString(R.string.staff_media),
            getString(R.string.staff_exit)
        )
        staffMenu = AlertDialog.Builder(this, R.style.Theme_NogaMT_Dialog)
            .setTitle(R.string.staff_menu_title)
            .setItems(options) { dialog, which ->
                dialog.dismiss()
                when (which) {
                    0 -> Unit
                    1 -> reloadWebApp(force = false)
                    2 -> openStaffSettings()
                    3 -> openMediaManager()
                    4 -> confirmExit()
                }
            }
            .setOnDismissListener { enterImmersive() }
            .create()
            .also { it.show() }
    }

    private fun confirmExit() {
        AlertDialog.Builder(this, R.style.Theme_NogaMT_Dialog)
            .setTitle(R.string.staff_exit_confirm_title)
            .setMessage(R.string.staff_exit_confirm_message)
            .setNegativeButton(android.R.string.cancel) { d, _ -> d.dismiss() }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                Log.i(Constants.LOG, "Staff exit confirmed")
                finishAndRemoveTask()
            }
            .show()
    }

    private fun openStaffSettings() {
        staffLauncher.launch(Intent(this, StaffSettingsActivity::class.java))
    }

    private fun openMediaManager() {
        staffLauncher.launch(Intent(this, MediaManagerActivity::class.java))
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onResume() {
        super.onResume()
        applyWindowPreferences()
        webView?.onResume()
        webView?.resumeTimers()

        val away = if (backgroundedAt == 0L) 0L else SystemClock.elapsedRealtime() - backgroundedAt
        backgroundedAt = 0L
        if (away > Constants.STALE_BACKGROUND_MS && player?.isActive != true) {
            Log.i(Constants.LOG, "Away for ${away / 1000}s - refreshing web app")
            reloadWebApp(force = false)
        }
        MediaRepository.verifySourceAvailability(this)
    }

    override fun onPause() {
        backgroundedAt = SystemClock.elapsedRealtime()
        player?.pause()
        webView?.onPause()
        webView?.pauseTimers()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        staffMenu?.dismiss()
        staffMenu = null
        networkMonitor.stop()
        player?.release()
        player = null
        destroyWebView()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_FROM_BOOT = "com.nogamt.showroom.FROM_BOOT"
        const val EXTRA_STAFF_ACTION = "com.nogamt.showroom.STAFF_ACTION"
        const val ACTION_RELOAD = "reload"
        const val ACTION_FORCE_REFRESH = "force_refresh"

        const val EVENT_STARTED = "nogamt-local-video-started"
        const val EVENT_ENDED = "nogamt-local-video-ended"
        const val EVENT_STOPPED = "nogamt-local-video-stopped"
        const val EVENT_ERROR = "nogamt-local-video-error"
    }
}
