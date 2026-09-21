package com.robroute.camera.hotspot

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Uses a network-specific socket so camera/server traffic is not routed through ESP32 setup AP. */
class EspSetupProvisioner(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "EspSetupProvisioning").apply { isDaemon = true }
    }
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun send(
        setupSsid: String,
        setupPassword: String,
        mode: String,
        targetSsid: String,
        targetPassword: String,
        onResult: (Boolean, String) -> Unit,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            onResult(false, "Automatic ESP32 setup requires Android 10 or newer")
            return
        }
        cancel()
        val done = AtomicBoolean(false)
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(setupSsid)
            .setWpa2Passphrase(setupPassword)
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                io.execute {
                    val result = runCatching { sendOnNetwork(network, mode, targetSsid, targetPassword) }
                        .fold({ true to it }, { false to (it.message ?: "ESP32 setup failed") })
                    if (done.compareAndSet(false, true)) {
                        main.post {
                            cancel()
                            onResult(result.first, result.second)
                        }
                    }
                }
            }

            override fun onUnavailable() {
                if (done.compareAndSet(false, true)) main.post {
                    cancel()
                    onResult(false, "Android could not join ESP32 setup Wi-Fi")
                }
            }

            override fun onLost(network: Network) {
                if (done.compareAndSet(false, true)) main.post {
                    cancel()
                    onResult(false, "ESP32 setup Wi-Fi disconnected before confirmation")
                }
            }
        }
        callback = networkCallback
        try {
            connectivity.requestNetwork(request, networkCallback, 20_000)
        } catch (error: Throwable) {
            cancel()
            onResult(false, error.message ?: "Could not request ESP32 setup network")
        }
    }

    private fun sendOnNetwork(network: Network, mode: String, ssid: String, password: String): String {
        val command = if (mode == "USB") "CONFIG USB" else {
            val encodedSsid = Base64.encodeToString(ssid.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
            val encodedPassword = Base64.encodeToString(password.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
            "CONFIG $mode $encodedSsid $encodedPassword"
        }
        val socket = network.socketFactory.createSocket() as Socket
        socket.use {
            it.connect(InetSocketAddress("192.168.4.1", 3333), 5_000)
            it.soTimeout = 5_000
            it.getOutputStream().write("$command\n".toByteArray(StandardCharsets.US_ASCII))
            it.getOutputStream().flush()
            val reader = it.getInputStream().bufferedReader(StandardCharsets.US_ASCII)
            repeat(8) {
                val line = reader.readLine() ?: error("ESP32 closed the setup connection")
                if (line.startsWith("OK CONFIG_SAVED mode=")) return "Saved ${line.substringAfter("mode=")} mode on ESP32"
                if (line.startsWith("ERR ")) error(line)
            }
            error("ESP32 did not confirm configuration")
        }
    }

    fun cancel() {
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        callback = null
    }
}
