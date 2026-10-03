package com.me2.android.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import androidx.annotation.StringRes
import com.me2.android.R

/**
 * Estado real de red para la barra superior (AUTO-SYNC // ACTIVE ↔ OFFLINE).
 * Escucha la red por defecto con ConnectivityManager.NetworkCallback y publica cambios en el hilo principal.
 */
class NetworkStatusMonitor(context: Context, private val onChange: (online: Boolean) -> Unit) {
    private val connectivity = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val main = Handler(Looper.getMainLooper())
    private var last: Boolean? = null
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = publish(hasInternet(network))
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
            publish(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        override fun onLost(network: Network) = publish(currentlyOnline())
    }

    fun start() {
        publish(currentlyOnline())
        if (!registered) registered = runCatching { connectivity.registerDefaultNetworkCallback(callback) }.isSuccess
    }

    fun stop() {
        if (registered) runCatching { connectivity.unregisterNetworkCallback(callback) }
        registered = false
    }

    private fun hasInternet(network: Network?): Boolean = network != null &&
        connectivity.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    private fun currentlyOnline(): Boolean = runCatching { hasInternet(connectivity.activeNetwork) }.getOrDefault(false)

    private fun publish(online: Boolean) {
        main.post {
            if (last != online) {
                last = online
                onChange(online)
            }
        }
    }

    companion object {
        @StringRes
        fun labelFor(online: Boolean): Int = if (online) R.string.autosync_active else R.string.autosync_offline
    }
}
