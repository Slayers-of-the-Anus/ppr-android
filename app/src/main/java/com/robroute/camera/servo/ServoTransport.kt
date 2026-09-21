package com.robroute.camera.servo

sealed interface ServoTransportState {
    data class Connected(val device: String) : ServoTransportState
    data object PermissionRequired : ServoTransportState
    data class Disconnected(val detail: String) : ServoTransportState
}

enum class ServoTransportMode {
    USB,
    WIFI,
    SHARED,
}

object ServoErrorCode {
    const val USB_NOT_FOUND = "USB_NOT_FOUND"
    const val USB_PERMISSION_DENIED = "USB_PERMISSION_DENIED"
    const val USB_OPEN_FAILED = "USB_OPEN_FAILED"
    const val USB_DISCONNECTED = "USB_DISCONNECTED"
    const val SERIAL_WRITE_FAILED = "SERIAL_WRITE_FAILED"
    const val SERIAL_TIMEOUT = "SERIAL_TIMEOUT"
    const val WIFI_NOT_FOUND = "WIFI_NOT_FOUND"
    const val WIFI_CONNECT_FAILED = "WIFI_CONNECT_FAILED"
    const val WIFI_DISCONNECTED = "WIFI_DISCONNECTED"
    const val WIFI_WRITE_FAILED = "WIFI_WRITE_FAILED"
    const val ESP32_ERROR = "ESP32_ERROR"
    const val SERVO_NOT_READY = "SERVO_NOT_READY"
}

interface ServoTransport {
    interface Listener {
        fun onTransportState(state: ServoTransportState)
        fun onSerialData(data: ByteArray)
        fun onTransportError(code: String, detail: String)
    }

    fun start()
    fun stop()
    fun moveTo(angle: Int)
    fun requestStatus()
    fun configureWifi(ssid: String, password: String)
    fun configureMode(mode: String, ssid: String, password: String)
}
