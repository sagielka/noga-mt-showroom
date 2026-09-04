package com.nogamt.showroom.bridge

import com.nogamt.showroom.Constants

/**
 * The `window.NogaAndroidTV` facade.
 *
 * The raw @JavascriptInterface object can only exchange primitives and strings, so this thin
 * shim parses JSON, provides promises-free synchronous helpers, and fires a
 * `nogamt-bridge-ready` event once installed.
 *
 * Injected with WebViewCompat.addDocumentStartJavaScript when the WebView supports it (so it
 * exists before any page script runs), otherwise at onPageStarted.
 */
object BridgeScript {

    val SOURCE: String = """
    (function () {
      var native = window.${Constants.JS_NATIVE_OBJECT};
      if (!native) { return; }
      if (window.NogaAndroidTV && window.NogaAndroidTV.__installed) { return; }

      function parse(json, fallback) {
        try { return json ? JSON.parse(json) : fallback; } catch (e) { return fallback; }
      }

      var api = {
        __installed: true,
        platform: 'android-tv',

        isAndroidTV: function () {
          try { return !!native.isAndroidTV(); } catch (e) { return false; }
        },
        getAppVersion: function () {
          try { return native.getAppVersion(); } catch (e) { return null; }
        },
        hasLocalVideo: function (id) {
          try { return !!native.hasLocalVideo(String(id)); } catch (e) { return false; }
        },
        getLocalVideoInfo: function (id) {
          try { return parse(native.getLocalVideoInfo(String(id)), null); } catch (e) { return null; }
        },
        listLocalVideos: function () {
          try { return parse(native.listLocalVideos(), []); } catch (e) { return []; }
        },
        playLocalVideo: function (id) {
          try { return !!native.playLocalVideo(String(id)); } catch (e) { return false; }
        },
        stopLocalVideo: function () {
          try { native.stopLocalVideo(); } catch (e) {}
        },
        isLocalVideoPlaying: function () {
          try { return !!native.isLocalVideoPlaying(); } catch (e) { return false; }
        },
        getMediaDiagnostics: function () {
          try { return parse(native.getMediaDiagnostics(), null); } catch (e) { return null; }
        },
        openMediaSettings: function () {
          try { native.openMediaSettings(); } catch (e) {}
        },
        refreshLocalMediaIndex: function () {
          try { native.refreshLocalMediaIndex(); } catch (e) {}
        },
        reportPlaylist: function (ids) {
          try { native.reportPlaylist(JSON.stringify(ids || [])); } catch (e) {}
        },
        log: function (message) {
          try { native.log(String(message)); } catch (e) {}
        }
      };

      Object.defineProperty(window, 'NogaAndroidTV', {
        value: api, writable: false, configurable: false, enumerable: true
      });

      window.dispatchEvent(new CustomEvent('nogamt-bridge-ready', {
        detail: { version: api.getAppVersion(), platform: 'android-tv' }
      }));
    })();
    """.trimIndent()
}
