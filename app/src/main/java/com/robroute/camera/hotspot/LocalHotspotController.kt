package com.robroute.camera.hotspot

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

data class HotspotSnapshot(
    val state: State = State.STOPPED,
    val ssid: String = "",
    val password: String = "",
    val detail: String = "",
) {
    enum class State { STOPPED, STARTING, RUNNING, ERROR }
}

class LocalHotspotController(
    context: Context,
    private val onState: (HotspotSnapshot) -> Unit,
) {
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val main = Handler(Looper.getMainLooper())
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var starting = false
    private var snapshot = HotspotSnapshot()

    fun current(): HotspotSnapshot = snapshot

    fun start() {
        if (starting || reservation != null) {
            publish(snapshot)
            return
        }
        starting = true
        publish(HotspotSnapshot(HotspotSnapshot.State.STARTING, detail = "Starting local-only hotspot"))
        try {
            wifiManager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(value: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = value
                    starting = false
                    val credentials = readCredentials(value)
                    if (credentials == null) {
                        try { value.close() } catch (_: Throwable) { }
                        reservation = null
                        publish(HotspotSnapshot(HotspotSnapshot.State.ERROR, detail = "Android did not provide hotspot credentials"))
                        return
                    }
                    val (ssid, password) = credentials
                    Log.i(TAG, "Local-only hotspot started; credentials acquired")
                    publish(HotspotSnapshot(HotspotSnapshot.State.RUNNING, ssid, password, "Local network only; no Internet sharing"))
                }

                override fun onStopped() {
                    reservation = null
                    starting = false
                    publish(HotspotSnapshot(HotspotSnapshot.State.STOPPED, detail = "Hotspot stopped by Android"))
                }

                override fun onFailed(reason: Int) {
                    reservation = null
                    starting = false
                    publish(HotspotSnapshot(HotspotSnapshot.State.ERROR, detail = failureMessage(reason)))
                }
            }, main)
        } catch (error: SecurityException) {
            starting = false
            publish(HotspotSnapshot(HotspotSnapshot.State.ERROR, detail = "Wi-Fi permission denied"))
        } catch (error: Throwable) {
            starting = false
            publish(HotspotSnapshot(HotspotSnapshot.State.ERROR, detail = error.message ?: error.javaClass.simpleName))
        }
    }

    fun stop() {
        starting = false
        val current = reservation
        reservation = null
        try { current?.close() } catch (_: Throwable) { }
        publish(HotspotSnapshot(HotspotSnapshot.State.STOPPED, detail = "Hotspot stopped"))
    }

    @Suppress("DEPRECATION")
    private fun readCredentials(value: WifiManager.LocalOnlyHotspotReservation): Pair<String, String>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val config = value.softApConfiguration
            val ssid = config.ssid.orEmpty()
            val password = config.passphrase.orEmpty()
            if (ssid.isBlank() || password.isBlank()) null else ssid to password
        } else {
            val config = value.wifiConfiguration ?: return null
            val ssid = config.SSID?.removeSurrounding("\"").orEmpty()
            val password = config.preSharedKey?.removeSurrounding("\"").orEmpty()
            if (ssid.isBlank() || password.isBlank()) null else ssid to password
        }
    }

    private fun publish(value: HotspotSnapshot) {
        snapshot = value
        onState(value)
    }

    private fun failureMessage(reason: Int): String = when (reason) {
        WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL -> "No Wi-Fi channel is available"
        WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE -> "Disable system tethering/Wi-Fi conflict and retry"
        WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED -> "Hotspot is disallowed by Android or the carrier"
        else -> "Android hotspot error ($reason)"
    }

    companion object { private const val TAG = "PhonePilotHotspot" }
}
