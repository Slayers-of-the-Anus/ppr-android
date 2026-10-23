package com.robroute.camera.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.Executors

/** Discovers a private listener, verifies it on the Wi-Fi Network, then picks a socket route. */
class NetworkRouter(
    context: Context,
    private val onRoute: (NetworkPath?, Network?) -> Unit,
) {
    private val app = context.applicationContext
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val preferences = app.getSharedPreferences("robroute", Context.MODE_PRIVATE)
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val state = PathStateMachine()
    private var wifi: Network? = null
    private var cellular: Network? = null
    private var discovered: String? = null
    private var publicBase = ""
    private var fallback = ""
    private var active = false
    private var version = 0L
    private val callbacks = mutableListOf<ConnectivityManager.NetworkCallback>()
    private var discovery: NsdManager.DiscoveryListener? = null
    private val scheduledRefresh = Runnable { evaluate() }

    fun start(lanFallback: String, publicUrl: String) {
        stop()
        fallback = lanFallback.trim().trimEnd('/')
        publicBase = publicUrl.trim().trimEnd('/')
        active = true
        for (transport in listOf(NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_CELLULAR)) {
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = refresh()
                override fun onLost(network: Network) = refresh()
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = refresh()
            }
            connectivity.registerNetworkCallback(NetworkRequest.Builder().addTransportType(transport).build(), cb)
            callbacks.add(cb)
        }
        startDiscovery()
        refresh()
    }

    fun stop() {
        active = false
        version++
        callbacks.forEach { runCatching { connectivity.unregisterNetworkCallback(it) } }
        callbacks.clear()
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discovery = null
        wifi = null
        cellular = null
        discovered = null
        state.reset()
        main.removeCallbacksAndMessages(null)
    }

    fun shutdown() { stop(); worker.shutdownNow() }

    fun retry() = refresh()

    private fun startDiscovery() {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!active) return
                @Suppress("DEPRECATION")
                nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val address = info.host as? Inet4Address ?: return
                        if (info.port !in 1..65535) return
                        discovered = "http://${address.hostAddress}:${info.port}"
                        refresh()
                    }
                })
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) { discovered = null; refresh() }
        }
        discovery = listener
        runCatching { nsd.discoverServices("_phonepilot._tcp.", NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    private fun refresh() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { refresh() }; return }
        if (!active) return
        main.removeCallbacks(scheduledRefresh)
        main.postDelayed(scheduledRefresh, 250)
    }

    private fun evaluate() {
        if (!active) return
        val networks = connectivity.allNetworks.toList()
        wifi = networks.firstOrNull { connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        cellular = networks.firstOrNull { connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true }
        val token = ++version
        val selectedWifi = wifi
        val selectedCellular = cellular
        val localCandidates = listOf(fallback, discovered.orEmpty()).filter(String::isNotBlank).distinct()
        worker.execute {
            val expected = preferences.getString("serverId", null)
            val publicId = if (expected == null && publicBase.isNotBlank()) {
                listOfNotNull(selectedCellular, selectedWifi).firstNotNullOfOrNull { readInfo(publicBase, it) }
            } else null
            val localId = if (expected == null && publicId == null && selectedWifi != null) {
                localCandidates.firstNotNullOfOrNull { candidate ->
                    if (isPrivateCandidate(candidate)) readInfo(candidate, selectedWifi) else null
                }
            } else null
            val serverId = expected ?: publicId ?: localId
            if (serverId != null && expected == null) preferences.edit().putString("serverId", serverId).apply()
            val lan = if (selectedWifi != null && serverId != null) localCandidates.firstNotNullOfOrNull { candidate ->
                if (isPrivateCandidate(candidate) && probe(candidate, selectedWifi, serverId)) NetworkPath(candidate, "wifi", true) else null
            } else null
            val publicNetwork = if (publicBase.startsWith("https://") && serverId != null) {
                listOfNotNull(selectedCellular, selectedWifi).firstOrNull { readInfo(publicBase, it) == serverId }
            } else null
            val public = if (publicNetwork != null) {
                NetworkPath(publicBase, if (publicNetwork == selectedCellular) "cellular" else "wifi", false)
            } else null
            main.post {
                if (!active || token != version) return@post
                val selected = state.choose(lan, public, android.os.SystemClock.elapsedRealtime())
                onRoute(selected, if (selected?.lan == true) selectedWifi else publicNetwork)
                if (lan != null && selected != lan) main.postDelayed({ refresh() }, 2_100)
                if (selected == null) main.postDelayed({ refresh() }, 4_000)
            }
        }
    }

    private fun readInfo(base: String, network: Network?): String? = runCatching {
        val url = URL("${base.trimEnd('/')}/api/network/info")
        val connection = (network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
        connection.connectTimeout = 2500
        connection.readTimeout = 2500
        try {
            if (connection.responseCode != 200) return null
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val id = json.optString("serverId")
            if (json.optInt("v") == 2 && runCatching { java.util.UUID.fromString(id).version() == 4 }.getOrDefault(false)) id else null
        } finally { connection.disconnect() }
    }.getOrNull()

    private fun probe(base: String, network: Network, expected: String): Boolean = runCatching {
        val nonceBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val nonce = Base64.encodeToString(nonceBytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val connection = network.openConnection(URL("${base.trimEnd('/')}/api/network/lan-probe?nonce=$nonce")) as HttpURLConnection
        connection.connectTimeout = 2000
        connection.readTimeout = 2000
        try {
            if (connection.responseCode != 200) return false
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            json.optInt("v") == 2 && json.optString("nonce") == nonce && json.optString("serverId") == expected
        } finally { connection.disconnect() }
    }.getOrDefault(false)

    private fun isPrivateCandidate(base: String): Boolean = runCatching {
        val host = URL(base).host
        val parts = host.split('.').map { it.toInt() }
        parts.size == 4 && parts.all { it in 0..255 } &&
            (parts[0] == 10 || (parts[0] == 172 && parts[1] in 16..31) || (parts[0] == 192 && parts[1] == 168))
    }.getOrDefault(false)
}
