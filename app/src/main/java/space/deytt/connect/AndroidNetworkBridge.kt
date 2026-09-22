package space.deytt.connect

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.system.OsConstants
import android.util.Log
import io.nekohasekai.libbox.ExchangeContext
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.StringIterator
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import io.nekohasekai.libbox.NetworkInterface as LibboxNetworkInterface

/** Android network services required by libbox's local DNS and auto-detection. */
internal class AndroidNetworkBridge(context: Context) {
    companion object {
        private const val TAG = "deytt-network"
        private const val RCODE_NXDOMAIN = 3
    }

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val callbacks = ConcurrentHashMap<InterfaceUpdateListener, ConnectivityManager.NetworkCallback>()
    @Volatile
    private var currentNetwork: Network? = null

    private val localResolver = object : LocalDNSTransport {
        override fun raw(): Boolean = false

        override fun exchange(ctx: ExchangeContext, message: ByteArray) {
            error("Raw DNS exchange is disabled")
        }

        override fun lookup(ctx: ExchangeContext, network: String, domain: String) {
            val answer = try {
                underlyingNetwork().getAllByName(domain).asSequence()
                    .filter {
                        when {
                            network.endsWith("4") -> it is Inet4Address
                            network.endsWith("6") -> it is Inet6Address
                            else -> true
                        }
                    }
                    .mapNotNull { it.hostAddress }
                    .toList()
            } catch (_: UnknownHostException) {
                ctx.errorCode(RCODE_NXDOMAIN)
                return
            }
            if (answer.isEmpty()) {
                ctx.errorCode(RCODE_NXDOMAIN)
            } else {
                ctx.success(answer.joinToString("\n"))
            }
        }
    }

    fun localDnsTransport(): LocalDNSTransport = localResolver

    fun interfaces(): NetworkInterfaceIterator {
        val interfaces = connectivity.allNetworks.mapNotNull { network ->
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
            val link = connectivity.getLinkProperties(network) ?: return@mapNotNull null
            val name = link.interfaceName ?: return@mapNotNull null
            val systemInterface = runCatching { NetworkInterface.getByName(name) }.getOrNull()
                ?: return@mapNotNull null

            LibboxNetworkInterface().apply {
                this.name = name
                index = systemInterface.index
                mtu = runCatching { systemInterface.mtu }.getOrDefault(0)
                dnsServer = StringListIterator(link.dnsServers.mapNotNull { it.hostAddress })
                gateway = StringListIterator(
                    link.routes.asSequence()
                        .filter { it.destination.prefixLength == 0 }
                        .mapNotNull { it.gateway }
                        .filterNot { it.isAnyLocalAddress }
                        .mapNotNull { it.hostAddress }
                        .toList(),
                )
                addresses = StringListIterator(
                    systemInterface.interfaceAddresses.mapNotNull { address ->
                        val host = address.address?.hostAddress?.substringBefore('%') ?: return@mapNotNull null
                        "$host/${address.networkPrefixLength}"
                    },
                )
                type = when {
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                    else -> Libbox.InterfaceTypeOther
                }
                var interfaceFlags = 0
                if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    interfaceFlags = OsConstants.IFF_UP or OsConstants.IFF_RUNNING
                }
                if (systemInterface.isLoopback) interfaceFlags = interfaceFlags or OsConstants.IFF_LOOPBACK
                if (systemInterface.isPointToPoint) interfaceFlags = interfaceFlags or OsConstants.IFF_POINTOPOINT
                if (systemInterface.supportsMulticast()) interfaceFlags = interfaceFlags or OsConstants.IFF_MULTICAST
                flags = interfaceFlags
                metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            }
        }
        return NetworkListIterator(interfaces)
    }

    fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        if (callbacks.containsKey(listener)) return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                currentNetwork = network
                publishDefaultInterface(listener, network)
            }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                currentNetwork = network
                publishDefaultInterface(listener, network)
            }
            override fun onLost(network: Network) {
                if (currentNetwork == network) currentNetwork = null
                runCatching { publishDefaultInterface(listener, underlyingNetwork()) }
                    .onFailure { listener.updateDefaultInterface("", -1, false, false) }
            }
        }
        callbacks[listener] = callback
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        connectivity.requestNetwork(request, callback)
        runCatching { publishDefaultInterface(listener, underlyingNetwork()) }
            .onFailure { Log.w(TAG, "No physical default network during monitor start", it) }
    }

    fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        val callback = callbacks.remove(listener) ?: return
        runCatching { connectivity.unregisterNetworkCallback(callback) }
    }

    private fun publishDefaultInterface(listener: InterfaceUpdateListener, network: Network) {
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
        val name = connectivity.getLinkProperties(network)?.interfaceName ?: return
        val index = runCatching { NetworkInterface.getByName(name)?.index }.getOrNull() ?: return
        listener.updateDefaultInterface(name, index, false, false)
    }

    private fun underlyingNetwork(): Network {
        currentNetwork?.let { network ->
            val capabilities = connectivity.getNetworkCapabilities(network)
            if (capabilities != null &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            ) {
                return network
            }
        }
        val candidates = connectivity.allNetworks.asSequence()
            .mapNotNull { network ->
                val capabilities = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                    !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                ) {
                    return@mapNotNull null
                }
                network to capabilities
            }
            .toList()
        return candidates.firstOrNull { (_, capabilities) ->
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }?.first ?: candidates.firstOrNull()?.first ?: error("Нет доступной физической сети")
    }
}

private class StringListIterator(values: List<String>) : StringIterator {
    private val iterator = values.iterator()
    private val size = values.size

    override fun hasNext(): Boolean = iterator.hasNext()
    override fun len(): Int = size
    override fun next(): String = iterator.next()
}

private class NetworkListIterator(values: List<LibboxNetworkInterface>) : NetworkInterfaceIterator {
    private val iterator = values.iterator()

    override fun hasNext(): Boolean = iterator.hasNext()
    override fun next(): LibboxNetworkInterface = iterator.next()
}
