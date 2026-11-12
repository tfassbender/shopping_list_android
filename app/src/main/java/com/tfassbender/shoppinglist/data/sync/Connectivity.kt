package com.tfassbender.shoppinglist.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** Whether the device has a network (as the web app's `navigator.onLine`). */
class Connectivity(context: Context) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val connected = MutableStateFlow(currentlyConnected())
    val hasNetwork: StateFlow<Boolean> = connected.asStateFlow()

    init {
        manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                connected.value = true
            }

            override fun onLost(network: Network) {
                connected.value = currentlyConnected()
            }
        })
    }

    private fun currentlyConnected(): Boolean =
        manager.getNetworkCapabilities(manager.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
}

/**
 * True if the server is presumably reachable: there is a network and the last sync didn't fail with a
 * network error (a WLAN without internet counts as a network). Same rule as the web app's `useOnline`.
 */
fun onlineFlow(connectivity: Connectivity, engine: SyncEngine): Flow<Boolean> =
    combine(connectivity.hasNetwork, engine.status) { network, status -> network && status != SyncStatus.OFFLINE }
        .distinctUntilChanged()
