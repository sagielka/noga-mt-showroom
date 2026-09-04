package com.nogamt.showroom.web

import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import com.nogamt.showroom.Constants

/**
 * Handles HTML5 fullscreen video (the Lovable player uses it) and keeps console output
 * in logcat for on-site debugging.
 */
class ShowroomWebChromeClient(private val callbacks: Callbacks) : WebChromeClient() {

    interface Callbacks {
        fun onEnterHtmlFullscreen(view: View, callback: CustomViewCallback)
        fun onExitHtmlFullscreen()
        fun onProgress(progress: Int)
    }

    private var customView: View? = null
    private var customViewCallback: CustomViewCallback? = null

    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
        if (customView != null) {
            callback.onCustomViewHidden()
            return
        }
        customView = view
        customViewCallback = callback
        callbacks.onEnterHtmlFullscreen(view, callback)
    }

    override fun onHideCustomView() {
        customView = null
        runCatching { customViewCallback?.onCustomViewHidden() }
        customViewCallback = null
        callbacks.onExitHtmlFullscreen()
    }

    override fun onProgressChanged(view: android.webkit.WebView?, newProgress: Int) {
        callbacks.onProgress(newProgress)
    }

    override fun onPermissionRequest(request: PermissionRequest) {
        // The showroom needs no camera or microphone. Deny everything, quietly.
        Log.i(Constants.LOG, "Denied web permission request: ${request.resources.joinToString()}")
        request.deny()
    }

    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
        if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
            Log.w(
                Constants.LOG,
                "[js] ${message.message()} @${message.sourceId()}:${message.lineNumber()}"
            )
        }
        return true
    }
}
