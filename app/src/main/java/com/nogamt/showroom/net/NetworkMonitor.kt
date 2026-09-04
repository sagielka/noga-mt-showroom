package com.nogamt.showroom.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.nogamt.showroom.Constants

/**
 * Thin wrapper over ConnectivityManager. Used to retry a failed load the moment the
 * exhibition Wi-Fi comes back, instead of waiting out the back-off timer.
 */
class NetworkMonitor(context: Context) {

    fun interface Listener {
        fun onConnectivityChanged(online: Boolean)
    }

    private val cm =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var listener: Listener? = null
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.i(Constants.LOG, "Network available")
            listener?.onConnectivityChanged(true)
        }

        override fun onLost(network: Network) {
            Log.w(Constants.LOG, "Network lost")
            listener?.onConnectivityChanged(isOnline())
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (validated) listener?.onConnectivityChanged(true)
        }
    }

    fun start(listener: Listener) {
        this.listener = listener
        if (registered) return
        runCatching {
            cm.registerDefaultNetworkCallback(callback)
            registered = true
        }.onFailure { Log.w(Constants.LOG, "Could not register network callback", it) }
    }

    fun stop() {
        listener = null
        if (!registered) return
        runCatching { cm.unregisterNetworkCallback(callback) }
        registered = false
    }

    fun isOnline(): Boolean {
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun describe(): String {
        val network = cm.activeNetwork ?: return "Disconnected"
        val caps = cm.getNetworkCapabilities(network) ?: return "Disconnected"
        val kind = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            else -> "Connected"
        }
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return if (validated) "$kind · internet OK" else "$kind · no internet"
    }
}
