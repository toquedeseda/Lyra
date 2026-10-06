package com.lyra.music.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** ¿Hay internet? Para el modo sin conexión (Inicio con lo descargado, canciones no disponibles en gris…). */
class NetworkMonitor(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val _online = MutableStateFlow(check())
    val online: StateFlow<Boolean> = _online.asStateFlow()

    init {
        runCatching {
            connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    _online.value = capabilities.hasInternet()
                }

                override fun onLost(network: Network) {
                    _online.value = check()
                }

                override fun onAvailable(network: Network) {
                    _online.value = check()
                }
            })
        }
    }

    val isOnline: Boolean get() = _online.value

    private fun check(): Boolean = runCatching {
        connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasInternet() == true
    }.getOrDefault(true)

    /**
     * Basta con una red con internet: en algunas wifis (empresa, instituto, VPN) la
     * comprobación de Google falla aunque YouTube funcione, y no hay que bloquear nada.
     */
    private fun NetworkCapabilities.hasInternet() = hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}
