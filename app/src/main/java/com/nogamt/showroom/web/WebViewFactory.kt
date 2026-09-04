package com.nogamt.showroom.web

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.nogamt.showroom.BuildConfig
import com.nogamt.showroom.Constants

/**
 * Builds a WebView configured for the NOGA MT PWA.
 *
 * Created in code (not in XML) so it can be destroyed and rebuilt after a renderer crash
 * without leaking the previous instance or its Activity context.
 */
object WebViewFactory {

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    fun create(context: Context): WebView {
        val webView = WebView(context)
        webView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        webView.setBackgroundColor(android.graphics.Color.BLACK)

        // TV: the WebView itself must take DPAD focus.
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true            // localStorage + sessionStorage
            databaseEnabled = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)

            // Autoplay: the attract loop must not need a remote press.
            mediaPlaybackRequiresUserGesture = false

            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = false
            displayZoomControls = false
            setSupportZoom(false)
            textZoom = 100

            // Cache: normal HTTP caching, so a published Lovable update is picked up,
            // but repeat launches are still fast. Force-refresh clears it explicitly.
            cacheMode = WebSettings.LOAD_DEFAULT

            // Security: no filesystem reach from web content.
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

            userAgentString = "$userAgentString NogaMTShowroom/${BuildConfig.VERSION_NAME} (AndroidTV)"
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            WebSettingsCompat.setSafeBrowsingEnabled(webView.settings, true)
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        Log.i(Constants.LOG, "WebView created, UA=${webView.settings.userAgentString}")
        return webView
    }

    /** Clears HTTP cache + service worker caches for a genuine "force refresh". */
    fun clearCaches(webView: WebView) {
        runCatching {
            webView.clearCache(true)
            webView.clearHistory()
            android.webkit.WebStorage.getInstance().deleteAllData()
        }.onFailure { Log.w(Constants.LOG, "Cache clear issue", it) }
    }
}
