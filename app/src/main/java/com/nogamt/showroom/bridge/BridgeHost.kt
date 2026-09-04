package com.nogamt.showroom.bridge

/**
 * Everything the JavaScript bridge is allowed to ask the Activity to do.
 * Implemented by MainActivity; all calls are marshalled to the main thread by [NogaBridge].
 */
interface BridgeHost {
    fun bridgePlayLocalVideo(id: String): Boolean
    fun bridgeStopLocalVideo()
    fun bridgeOpenMediaSettings()
    fun bridgeRefreshMediaIndex()
    fun bridgeIsLocalPlaybackActive(): Boolean
}
