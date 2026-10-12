package fr.antoineassocies.galene.domain

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Follows the system's default network (Wi-Fi, mobile data…).
 *
 * A change of default network means our sockets are bound to an address that is going
 * away (typically Wi-Fi → 4G when leaving home): the call is better rebuilt at once than
 * after the old connection times out.
 */
class NetworkMonitor(context: Context) {

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    /** Current default network, null while there is none. Emits once on collection. */
    val defaultNetwork: Flow<Network?> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(network)
            }
            override fun onLost(network: Network) {
                if (connectivity.activeNetwork == null) trySend(null)
            }
        }
        // No callback at all when offline at registration: start from the current state
        if (connectivity.activeNetwork == null) trySend(null)
        connectivity.registerDefaultNetworkCallback(callback)
        awaitClose { connectivity.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

    /**
     * Whether the default network is billed by volume (mobile data, tethering…): the call
     * then asks for lighter video. Emits once on collection.
     */
    val metered: Flow<Boolean> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
            }
        }
        trySend(connectivity.isActiveNetworkMetered)
        connectivity.registerDefaultNetworkCallback(callback)
        awaitClose { connectivity.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}
