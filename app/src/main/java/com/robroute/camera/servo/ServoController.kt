package com.robroute.camera.servo

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

class ServoController(
    context: Context,
    initialMode: ServoTransportMode = ServoTransportMode.USB,
    private val onStatus: (String) -> Unit,
    private val onState: (available: Boolean, angle: Int, min: Int, max: Int) -> Unit,
    private val onError: (code: String, detail: String) -> Unit,
) {
    private val usbListener = transportListener(ServoTransportMode.USB)
    private val wifiListener = transportListener(ServoTransportMode.WIFI)
    private val usbTransport: ServoTransport = UsbSerialServoTransport(context, usbListener)
    private val wifiTransport: ServoTransport = WifiServoTransport(wifiListener)
    private val parsers = mapOf(
        ServoTransportMode.USB to ServoLineParser(),
        ServoTransportMode.WIFI to ServoLineParser(),
    )
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val connected = mutableMapOf(
        ServoTransportMode.USB to false,
        ServoTransportMode.WIFI to false,
    )
    private var mode = initialMode
    private var available = false
    private var angle = 90
    private var minAngle = 0
    private var maxAngle = 180
    private var activeTarget: Int? = null
    private var latestTarget: Int? = null
    private var timeoutGeneration = 0L

    fun start() {
        emitStatus("esp32Link:${mode.displayName()}")
        usbTransport.start()
        wifiTransport.start()
    }

    fun stop() {
        usbTransport.stop()
        wifiTransport.stop()
    }

    fun setTransportMode(value: ServoTransportMode) {
        val isConnected: Boolean
        synchronized(lock) {
            if (mode != value) {
                mode = value
                available = false
                activeTarget = null
                latestTarget = null
                cancelTimeoutLocked()
                parsers[channel(value)]?.reset()
            }
            isConnected = connected[channel(value)] == true
        }
        emitStatus("esp32Link:${value.displayName()}")
        emitStatus("esp32:${if (isConnected) "connected · syncing" else "disconnected"}")
        emitStatus("servo:—")
        emitState(false, angle, minAngle, maxAngle)
        if (isConnected) transport(value).requestStatus()
    }

    fun transportMode(): ServoTransportMode = synchronized(lock) { mode }

    fun moveTo(requestedAngle: Int) {
        synchronized(lock) {
            if (!available) {
                emitError(ServoErrorCode.SERVO_NOT_READY, "ESP32 servo is not ready over ${mode.displayName()}")
                return
            }
            val target = requestedAngle.coerceIn(minAngle, maxAngle)
            if (activeTarget == null) startMoveLocked(target) else latestTarget = target
        }
    }

    fun publishSnapshot() {
        val snapshot = synchronized(lock) { listOf(if (available) 1 else 0, angle, minAngle, maxAngle) }
        emitState(snapshot[0] == 1, snapshot[1], snapshot[2], snapshot[3])
    }

    fun configureWifi(ssid: String, password: String): Boolean {
        return configureMode("HOTSPOT", ssid, password)
    }

    fun configureMode(mode: String, ssid: String, password: String): Boolean {
        if (!synchronized(lock) { connected[ServoTransportMode.USB] == true }) {
            emitStatus("esp32Wifi:connect ESP32 by USB first")
            emitError(ServoErrorCode.USB_DISCONNECTED, "Connect ESP32 over USB before sending hotspot credentials")
            return false
        }
        usbTransport.configureMode(mode, ssid, password)
        emitStatus("esp32Wifi:sending $mode configuration over USB")
        return true
    }

    private fun transportListener(source: ServoTransportMode) = object : ServoTransport.Listener {
        override fun onTransportState(state: ServoTransportState) = handleTransportState(source, state)
        override fun onSerialData(data: ByteArray) = handleTransportData(source, data)
        override fun onTransportError(code: String, detail: String) = handleTransportError(source, code, detail)
    }

    private fun handleTransportState(source: ServoTransportMode, state: ServoTransportState) {
        when (state) {
            is ServoTransportState.Connected -> {
                synchronized(lock) { connected[source] = true }
                emitStatus("${source.statusKey()}:connected · ${state.device}")
                if (isActive(source)) {
                    emitStatus("esp32:connected · syncing")
                    transport(source).requestStatus()
                }
            }
            ServoTransportState.PermissionRequired -> {
                synchronized(lock) { connected[source] = false }
                emitStatus("${source.statusKey()}:permission required")
                if (isActive(source)) {
                    markUnavailable()
                    emitStatus("esp32:permission required")
                }
            }
            is ServoTransportState.Disconnected -> {
                synchronized(lock) { connected[source] = false }
                val label = if (source == ServoTransportMode.WIFI) "searching · ${state.detail}" else "disconnected"
                emitStatus("${source.statusKey()}:$label")
                if (isActive(source)) {
                    markUnavailable()
                    emitStatus("esp32:${if (source == ServoTransportMode.WIFI) "searching" else "disconnected"}")
                }
            }
        }
    }

    private fun handleTransportData(source: ServoTransportMode, data: ByteArray) {
        val parser = parsers.getValue(source)
        for (response in parser.feed(data)) {
            when (response) {
                ServoResponse.WifiSaved -> emitStatus("esp32Wifi:credentials saved; ESP32 is connecting")
                is ServoResponse.ConfigSaved -> emitStatus("esp32Wifi:${response.mode} configuration saved")
                ServoResponse.WifiConnecting -> emitStatus("esp32Wifi:connecting")
                is ServoResponse.WifiConnected -> emitStatus("esp32Wifi:connected ${response.ip}")
                is ServoResponse.WifiStatus -> emitStatus(
                    "esp32Wifi:${response.state}${if (response.ip.isNotBlank()) " ${response.ip}" else ""}",
                )
                is ServoResponse.Invalid -> Log.d(TAG, "ignored ${source.name} line: ${response.line}")
                else -> if (isActive(source)) {
                    when (response) {
                        is ServoResponse.Status -> handleStatus(response)
                        is ServoResponse.AngleAccepted -> handleAngleAccepted(response.angle)
                        is ServoResponse.Error -> handleEsp32Error(response)
                        else -> Unit
                    }
                }
            }
        }
    }

    private fun handleTransportError(source: ServoTransportMode, code: String, detail: String) {
        val provisioningError = code.startsWith("WIFI_") && source == ServoTransportMode.USB
        if (isActive(source)) {
            if (code in DISCONNECT_ERRORS) markUnavailable()
            emitStatus("esp32:${if (code in DISCONNECT_ERRORS) "disconnected" else "error $code"}")
            emitError(code, detail)
        } else if (provisioningError) {
            emitStatus("esp32Wifi:error $code")
            emitError(code, detail)
        }
    }

    private fun handleStatus(status: ServoResponse.Status) {
        var next: Int? = null
        synchronized(lock) {
            minAngle = status.min
            maxAngle = status.max
            angle = status.angle.coerceIn(minAngle, maxAngle)
            available = status.ready
            if (activeTarget == null && available) {
                next = latestTarget
                latestTarget = null
            }
        }
        emitStatus("esp32:${if (status.ready) "connected" else "not ready"}")
        emitStatus("servo:${if (status.ready) "${status.angle}°" else "—"}")
        emitState(status.ready, angle, minAngle, maxAngle)
        next?.let { synchronized(lock) { if (activeTarget == null && available) startMoveLocked(it) } }
    }

    private fun handleAngleAccepted(confirmedAngle: Int) {
        var next: Int? = null
        synchronized(lock) {
            cancelTimeoutLocked()
            angle = confirmedAngle.coerceIn(minAngle, maxAngle)
            available = true
            activeTarget = null
            next = latestTarget
            latestTarget = null
        }
        emitStatus("esp32:connected")
        emitStatus("servo:$angle°")
        emitState(true, angle, minAngle, maxAngle)
        next?.let { target ->
            synchronized(lock) {
                if (activeTarget == null && target != angle) startMoveLocked(target)
            }
        }
    }

    private fun handleEsp32Error(error: ServoResponse.Error) {
        if (error.code.startsWith("WIFI_") ||
            (error.code == "UNKNOWN_COMMAND" && (error.detail.startsWith("WIFI_CONFIG") || error.detail.startsWith("CONFIG")))
        ) {
            emitStatus("esp32Wifi:error ${error.code}${if (error.detail.isNotBlank()) " ${error.detail}" else ""}")
            return
        }
        val next = synchronized(lock) {
            cancelTimeoutLocked()
            activeTarget = null
            latestTarget.also { latestTarget = null }
        }
        emitError(ServoErrorCode.ESP32_ERROR, listOf(error.code, error.detail).filter(String::isNotBlank).joinToString(" "))
        next?.let { synchronized(lock) { if (available) startMoveLocked(it) } } ?: activeTransport().requestStatus()
    }

    private fun startMoveLocked(target: Int) {
        activeTarget = target
        activeTransport().moveTo(target)
        val generation = ++timeoutGeneration
        main.postDelayed({ onCommandTimeout(generation, target) }, COMMAND_TIMEOUT_MS)
    }

    private fun onCommandTimeout(generation: Long, target: Int) {
        var next: Int? = null
        synchronized(lock) {
            if (generation != timeoutGeneration || activeTarget != target) return
            activeTarget = null
            next = latestTarget
            latestTarget = null
        }
        emitError(ServoErrorCode.SERIAL_TIMEOUT, "Timed out waiting for OK ANGLE $target")
        next?.let { synchronized(lock) { if (available) startMoveLocked(it) } } ?: activeTransport().requestStatus()
    }

    private fun activeTransport(): ServoTransport = transport(synchronized(lock) { mode })
    private fun transport(value: ServoTransportMode): ServoTransport =
        if (value == ServoTransportMode.USB) usbTransport else wifiTransport

    private fun channel(value: ServoTransportMode): ServoTransportMode =
        if (value == ServoTransportMode.SHARED) ServoTransportMode.WIFI else value

    private fun isActive(source: ServoTransportMode): Boolean = synchronized(lock) { channel(mode) == source }
    private fun cancelTimeoutLocked() { timeoutGeneration++ }

    private fun markUnavailable() {
        val snapshot = synchronized(lock) {
            available = false
            activeTarget = null
            latestTarget = null
            cancelTimeoutLocked()
            listOf(angle, minAngle, maxAngle)
        }
        emitStatus("servo:—")
        emitState(false, snapshot[0], snapshot[1], snapshot[2])
    }

    private fun ServoTransportMode.displayName() = when (this) {
        ServoTransportMode.USB -> "USB cable"
        ServoTransportMode.WIFI -> "Phone hotspot"
        ServoTransportMode.SHARED -> "Shared Wi-Fi"
    }
    private fun ServoTransportMode.statusKey() = if (this == ServoTransportMode.USB) "esp32Usb" else "esp32Network"
    private fun emitStatus(value: String) = main.post { onStatus(value) }
    private fun emitState(value: Boolean, angle: Int, min: Int, max: Int) = main.post { onState(value, angle, min, max) }
    private fun emitError(code: String, detail: String) = main.post { onError(code, detail) }

    companion object {
        private const val TAG = "ServoController"
        private const val COMMAND_TIMEOUT_MS = 1_500L
        private val DISCONNECT_ERRORS = setOf(
            ServoErrorCode.USB_DISCONNECTED,
            ServoErrorCode.USB_OPEN_FAILED,
            ServoErrorCode.USB_PERMISSION_DENIED,
            ServoErrorCode.USB_NOT_FOUND,
            ServoErrorCode.WIFI_DISCONNECTED,
            ServoErrorCode.WIFI_CONNECT_FAILED,
        )
    }
}
