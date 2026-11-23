package cz.starenka.jiffy

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import java.net.Inet4Address
import java.net.InetAddress

internal data class WifiEndpoint(val network: Long, val host: String)

internal class WifiEndpoints {
    private val hosts = linkedMapOf<Long, String>()
    @Volatile var selected: WifiEndpoint? = null
        private set

    fun update(network: Long, addresses: List<InetAddress>) {
        val ipv4 = addresses.filterIsInstance<Inet4Address>()
            .filterNot { it.isAnyLocalAddress || it.isLoopbackAddress || it.isMulticastAddress }
            .mapNotNull { it.hostAddress }
        val host = hosts[network]?.takeIf { it in ipv4 } ?: ipv4.firstOrNull()
        if (host == null) hosts.remove(network) else hosts[network] = host
        selected = hosts.entries.firstOrNull()?.let { WifiEndpoint(it.key, it.value) }
    }
}

internal object WifiLan : ConnectivityManager.NetworkCallback() {
    private val endpoints = WifiEndpoints()
    val endpoint get() = endpoints.selected
    var onChange: (() -> Unit)? = null

    fun watch(context: Context) {
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
        context.getSystemService(ConnectivityManager::class.java)
            .registerNetworkCallback(request, this, Handler(context.mainLooper))
    }

    override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
        update(network, properties.linkAddresses.map { it.address })
    }

    override fun onLost(network: Network) {
        update(network, emptyList())
    }

    private fun update(network: Network, addresses: List<InetAddress>) {
        val previous = endpoint
        endpoints.update(network.networkHandle, addresses)
        if (previous != endpoint) {
            onChange?.invoke()
            ServerState.changed()
        }
    }
}
