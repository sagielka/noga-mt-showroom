package com.nogamt.showroom.web

import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.Log
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.RequiresApi
import com.nogamt.showroom.Constants
import com.nogamt.showroom.bridge.BridgeScript

class ShowroomWebViewClient(private val callbacks: Callbacks) : WebViewClient() {

    interface Callbacks {
        fun onPageLoadStarted(url: String)
        fun onPageLoadFinished(url: String)
        fun onMainFrameFailure(reason: String)
        /** Return true if the host recreated the WebView and the process may continue. */
        fun onRendererGone(didCrash: Boolean): Boolean
        fun onExternalLinkBlocked(url: String)
    }

    private var sawMainFrameError = false

    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest
    ): Boolean {
        val uri = request.url
        val scheme = uri.scheme?.lowercase()

        if (scheme != "https" && scheme != "http") {
            // intent:, market:, mailto:, tel: - never leave the kiosk for these.
            Log.i(Constants.LOG, "Blocked non-web scheme: $scheme")
            return true
        }
        if (scheme == "http") {
            Log.w(Constants.LOG, "Blocked cleartext navigation to ${uri.host}")
            return true
        }
        if (Constants.isAllowedHost(uri.host)) return false

        // External https link: do not navigate away from the showroom.
        callbacks.onExternalLinkBlocked(uri.toString())
        return true
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        sawMainFrameError = false
        // Fallback injection for WebViews without DOCUMENT_START_SCRIPT support.
        view.evaluateJavascript(BridgeScript.SOURCE, null)
        callbacks.onPageLoadStarted(url)
    }

    override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        view.evaluateJavascript(BridgeScript.SOURCE, null)
        if (!sawMainFrameError) callbacks.onPageLoadFinished(url)
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError
    ) {
        super.onReceivedError(view, request, error)
        if (!request.isForMainFrame) return          // a failed asset must not blank the UI
        sawMainFrameError = true
        val description = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            "${error.errorCode} ${error.description}"
        } else {
            "load error"
        }
        Log.w(Constants.LOG, "Main frame error: $description (${request.url})")
        callbacks.onMainFrameFailure(description)
    }

    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse
    ) {
        super.onReceivedHttpError(view, request, errorResponse)
        if (!request.isForMainFrame) return
        if (errorResponse.statusCode >= 500) {
            sawMainFrameError = true
            callbacks.onMainFrameFailure("HTTP ${errorResponse.statusCode}")
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        val didCrash = detail.didCrash()
        Log.e(Constants.LOG, "WebView renderer gone (didCrash=$didCrash) - rebuilding")
        return callbacks.onRendererGone(didCrash)
    }

    // NOTE: onReceivedSslError is intentionally NOT overridden.
    // The default implementation cancels the load, i.e. certificates are always validated.

    companion object {
        fun hostOf(url: String): String? = runCatching { Uri.parse(url).host }.getOrNull()
    }
}
