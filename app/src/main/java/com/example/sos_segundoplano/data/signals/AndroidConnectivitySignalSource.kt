package com.example.sos_segundoplano.data.signals

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.example.sos_segundoplano.domain.signals.ConnectivityPolicy
import com.example.sos_segundoplano.domain.signals.NetworkTransport
import com.example.sos_segundoplano.domain.signals.SignalAvailability
import com.example.sos_segundoplano.domain.signals.SignalReading

class AndroidConnectivitySignalSource(
    context: Context,
    private val store: TripSignalStore
) : SignalSource {
    private val connectivityManager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private var started = false
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = publish(network)
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = publish(network)
        override fun onLost(network: Network) = publish(null)
        override fun onUnavailable() = publish(null)
    }

    override fun start() {
        if (started) return
        try {
            connectivityManager.registerNetworkCallback(NetworkRequest.Builder().build(), callback)
            started = true
            publish(connectivityManager.activeNetwork)
        } catch (_: SecurityException) {
            store.updateConnectivity(SignalReading(SignalAvailability.PermissionMissing))
        }
    }

    override fun stop() {
        if (!started) return
        try {
            connectivityManager.unregisterNetworkCallback(callback)
        } catch (_: IllegalArgumentException) {
        } catch (_: SecurityException) {
        }
        started = false
    }

    private fun publish(network: Network?) {
        val capabilities = network?.let(connectivityManager::getNetworkCapabilities)
        val hasNetwork = capabilities != null
        store.updateConnectivity(
            ConnectivityPolicy.reading(
                hasNetwork = hasNetwork,
                validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
                metered = hasNetwork && connectivityManager.isActiveNetworkMetered,
                transport = capabilities.toTransport(),
                timestampMillis = System.currentTimeMillis()
            )
        )
    }

    private fun NetworkCapabilities?.toTransport(): NetworkTransport = when {
        this == null -> NetworkTransport.None
        hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransport.Wifi
        hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransport.Cellular
        hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkTransport.Ethernet
        hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkTransport.Vpn
        hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> NetworkTransport.Bluetooth
        else -> NetworkTransport.Other
    }
}
