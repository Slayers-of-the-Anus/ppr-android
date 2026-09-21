package com.robroute.camera.servo

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import android.util.Base64
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class UsbSerialServoTransport(
    context: Context,
    private val listener: ServoTransport.Listener,
) : ServoTransport, SerialInputOutputManager.Listener {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ServoUSB").apply { isDaemon = true }
    }
    private val lock = Any()
    private var port: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private var connectedDeviceId: Int? = null
    @Volatile private var permissionRequestedDeviceId: Int? = null
    @Volatile private var started = false
    private var receiversRegistered = false
    private var notFoundReported = false

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != permissionAction) return
            val device = intent.usbDeviceExtra()
            permissionRequestedDeviceId = null
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && device != null) {
                Log.i(TAG, "permission granted")
                executor.execute { findDriver(device)?.let(::openDriver) ?: reportNotFound() }
            } else {
                Log.w(TAG, "permission denied")
                listener.onTransportState(ServoTransportState.Disconnected("USB permission denied"))
                listener.onTransportError(ServoErrorCode.USB_PERMISSION_DENIED, "USB serial permission was denied")
            }
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> executor.execute(::scanAndConnect)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val deviceId = intent.usbDeviceExtra()?.deviceId
                    if (deviceId == permissionRequestedDeviceId) permissionRequestedDeviceId = null
                    if (deviceId != null && deviceId == connectedDeviceId) {
                        executor.execute {
                            closePort()
                            listener.onTransportState(ServoTransportState.Disconnected("ESP32 USB serial device disconnected"))
                            listener.onTransportError(ServoErrorCode.USB_DISCONNECTED, "ESP32 USB serial device disconnected")
                        }
                    }
                }
            }
        }
    }

    override fun start() {
        if (started) return
        started = true
        registerReceivers()
        executor.execute(::scanAndConnect)
    }

    override fun stop() {
        if (!started) return
        started = false
        if (receiversRegistered) {
            try { appContext.unregisterReceiver(permissionReceiver) } catch (_: Throwable) { }
            try { appContext.unregisterReceiver(usbReceiver) } catch (_: Throwable) { }
            receiversRegistered = false
        }
        executor.execute(::closePort)
        executor.shutdown()
    }

    override fun moveTo(angle: Int) = writeLine("S$angle")
    override fun requestStatus() = writeLine("STATUS")
    override fun configureWifi(ssid: String, password: String) {
        val ssidEncoded = Base64.encodeToString(ssid.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
        val passwordEncoded = Base64.encodeToString(password.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
        writeLine("WIFI_CONFIG $ssidEncoded $passwordEncoded")
    }

    override fun configureMode(mode: String, ssid: String, password: String) {
        if (mode == "USB") {
            writeLine("CONFIG USB")
            return
        }
        val ssidEncoded = Base64.encodeToString(ssid.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
        val passwordEncoded = Base64.encodeToString(password.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
        writeLine("CONFIG $mode $ssidEncoded $passwordEncoded")
    }

    private fun registerReceivers() {
        ContextCompat.registerReceiver(
            appContext, permissionReceiver, IntentFilter(permissionAction), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ContextCompat.registerReceiver(
            appContext, usbReceiver,
            IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            },
            ContextCompat.RECEIVER_EXPORTED,
        )
        receiversRegistered = true
    }

    private fun scanAndConnect() {
        if (!started || synchronized(lock) { port != null }) return
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager).firstOrNull()
        if (driver == null) {
            reportNotFound()
            return
        }
        notFoundReported = false
        val device = driver.device
        Log.i(TAG, "device found: ${device.productName ?: device.deviceName} vid=${device.vendorId} pid=${device.productId}")
        if (!usbManager.hasPermission(device)) {
            if (permissionRequestedDeviceId == device.deviceId) return
            permissionRequestedDeviceId = device.deviceId
            listener.onTransportState(ServoTransportState.PermissionRequired)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val permissionIntent = PendingIntent.getBroadcast(
                appContext, 0, Intent(permissionAction).setPackage(appContext.packageName), flags,
            )
            usbManager.requestPermission(device, permissionIntent)
            return
        }
        openDriver(driver)
    }

    private fun findDriver(device: UsbDevice): UsbSerialDriver? =
        UsbSerialProber.getDefaultProber().probeDevice(device)

    private fun openDriver(driver: UsbSerialDriver) {
        if (!started || synchronized(lock) { port != null }) return
        permissionRequestedDeviceId = null
        val connection = usbManager.openDevice(driver.device)
        if (connection == null) {
            listener.onTransportState(ServoTransportState.Disconnected("USB serial open failed"))
            listener.onTransportError(ServoErrorCode.USB_OPEN_FAILED, "Could not open USB serial device")
            return
        }
        val selectedPort = driver.ports.firstOrNull()
        if (selectedPort == null) {
            connection.close()
            listener.onTransportError(ServoErrorCode.USB_OPEN_FAILED, "USB serial device has no ports")
            return
        }
        try {
            selectedPort.open(connection)
            selectedPort.setParameters(115200, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            val manager = SerialInputOutputManager(selectedPort, this)
            synchronized(lock) {
                port = selectedPort
                ioManager = manager
                connectedDeviceId = driver.device.deviceId
            }
            manager.start()
            val label = driver.device.productName ?: driver.device.deviceName
            Log.i(TAG, "serial opened 115200: $label")
            listener.onTransportState(ServoTransportState.Connected(label))
            writeLine("STATUS")
        } catch (error: Throwable) {
            try { selectedPort.close() } catch (_: Throwable) { }
            connection.close()
            listener.onTransportState(ServoTransportState.Disconnected("USB serial open failed"))
            listener.onTransportError(ServoErrorCode.USB_OPEN_FAILED, error.message ?: error.javaClass.simpleName)
        }
    }

    private fun writeLine(command: String) {
        if (!started) return
        executor.execute {
            val selectedPort = synchronized(lock) { port }
            if (selectedPort == null) {
                listener.onTransportError(ServoErrorCode.USB_DISCONNECTED, "USB serial device is not connected")
                return@execute
            }
            try {
                Log.i(TAG, "TX: ${if (command.startsWith("CONFIG ") || command.startsWith("WIFI_CONFIG ")) "[redacted configuration]" else command}")
                selectedPort.write("$command\n".toByteArray(StandardCharsets.US_ASCII), WRITE_TIMEOUT_MS)
            } catch (error: Throwable) {
                listener.onTransportError(ServoErrorCode.SERIAL_WRITE_FAILED, error.message ?: error.javaClass.simpleName)
            }
        }
    }

    override fun onNewData(data: ByteArray) {
        Log.d(TAG, "RX bytes: ${data.size}")
        listener.onSerialData(data.copyOf())
    }

    override fun onRunError(error: Exception) {
        executor.execute {
            closePort()
            listener.onTransportState(ServoTransportState.Disconnected("USB serial connection lost"))
            listener.onTransportError(ServoErrorCode.USB_DISCONNECTED, error.message ?: "USB serial connection lost")
        }
    }

    private fun closePort() {
        val manager: SerialInputOutputManager?
        val selectedPort: UsbSerialPort?
        synchronized(lock) {
            manager = ioManager
            selectedPort = port
            ioManager = null
            port = null
            connectedDeviceId = null
            permissionRequestedDeviceId = null
        }
        try { manager?.stop() } catch (_: Throwable) { }
        try { selectedPort?.close() } catch (_: Throwable) { }
    }

    private fun reportNotFound() {
        listener.onTransportState(ServoTransportState.Disconnected("no supported USB serial device"))
        if (!notFoundReported) {
            notFoundReported = true
            listener.onTransportError(ServoErrorCode.USB_NOT_FOUND, "No supported USB serial device found")
        }
    }

    private fun Intent.usbDeviceExtra(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else @Suppress("DEPRECATION") getParcelableExtra(UsbManager.EXTRA_DEVICE)

    private val permissionAction: String get() = "${appContext.packageName}.USB_PERMISSION"

    companion object {
        private const val TAG = "ServoUSB"
        private const val WRITE_TIMEOUT_MS = 1_000
    }
}
