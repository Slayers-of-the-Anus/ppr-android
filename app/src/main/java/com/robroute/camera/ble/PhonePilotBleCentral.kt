package com.robroute.camera.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.UUID

/** GATT central active only for the lifetime of the app service. */
@SuppressLint("MissingPermission")
class PhonePilotBleCentral(
    context: Context,
    private val onState: (String) -> Unit,
    private val onBoardStatus: (JSONObject) -> Unit,
) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("robroute", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private val adapter = app.getSystemService(BluetoothManager::class.java).adapter
    private val decoder = BleFrameDecoder()
    private val writes = ArrayDeque<ByteArray>()
    private var gatt: BluetoothGatt? = null
    private var command: BluetoothGattCharacteristic? = null
    private var status: BluetoothGattCharacteristic? = null
    private var device: BluetoothDevice? = null
    private var scanning = false
    private var active = false
    private var verified = false
    private var mtu = 23
    private var pendingId: String? = null
    private var lastStatus: JSONObject? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED || !active) return
            val changed = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            if (changed == null || changed.address != device?.address) return
            when (changed.bondState) {
                BluetoothDevice.BOND_BONDED -> connectDevice(changed)
                BluetoothDevice.BOND_NONE -> { onState("pairing failed"); reconnectLater() }
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!active || gatt != null) return
            val found = result.device
            val savedAddress = prefs.getString("bleAddress", null)
            if (savedAddress != null && found.address != savedAddress) return
            device = found
            stopScan()
            if (found.bondState == BluetoothDevice.BOND_BONDED) connectDevice(found)
            else {
                onState("pairing · enter PIN from ESP32 Serial Monitor")
                if (!found.createBond()) reconnectLater()
            }
        }
        override fun onScanFailed(errorCode: Int) { onState("scan error $errorCode"); reconnectLater() }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, statusCode: Int, newState: Int) {
            handler.post {
                if (g !== gatt) return@post
                if (statusCode == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    onState("connecting · discovering services")
                    runCatching { g.discoverServices() }.onFailure { reconnectLater() }
                } else {
                    verified = false; command = null; status = null; decoder.reset(); writes.clear()
                    pendingId = null; lastStatus = null
                    runCatching { g.close() }; gatt = null
                    onState("disconnected")
                    reconnectLater()
                }
            }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, statusCode: Int) {
            handler.post {
                if (g !== gatt || statusCode != BluetoothGatt.GATT_SUCCESS) { reconnectLater(); return@post }
                val service = g.getService(BleProtocol.SERVICE)
                command = service?.getCharacteristic(BleProtocol.COMMAND)
                status = service?.getCharacteristic(BleProtocol.STATUS)
                if (command == null || status == null || g.device.bondState != BluetoothDevice.BOND_BONDED) {
                    onState("error · GATT service or bond missing"); reconnectLater(); return@post
                }
                g.requestMtu(247)
                g.setCharacteristicNotification(status, true)
                val descriptor = status?.getDescriptor(CCCD)
                if (descriptor != null) {
                    if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    else { @Suppress("DEPRECATION") descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; @Suppress("DEPRECATION") g.writeDescriptor(descriptor) }
                } else g.readCharacteristic(status)
            }
        }
        override fun onMtuChanged(g: BluetoothGatt, value: Int, statusCode: Int) {
            if (g === gatt && statusCode == BluetoothGatt.GATT_SUCCESS) mtu = value
        }
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, statusCode: Int) {
            handler.post {
                if (g !== gatt) return@post
                if (statusCode == BluetoothGatt.GATT_SUCCESS) g.readCharacteristic(status)
                else { onState("error · notifications unavailable"); reconnectLater() }
            }
        }
        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, statusCode: Int) {
            if (g === gatt && statusCode == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == BleProtocol.STATUS) receive(value)
        }
        @Deprecated("API 32 callback")
        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, statusCode: Int) {
            if (Build.VERSION.SDK_INT < 33 && g === gatt && statusCode == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == BleProtocol.STATUS) receive(characteristic.value)
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (g === gatt && characteristic.uuid == BleProtocol.STATUS) receive(value)
        }
        @Deprecated("API 32 callback")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33 && g === gatt && characteristic.uuid == BleProtocol.STATUS) receive(characteristic.value)
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, statusCode: Int) {
            handler.post {
                if (g !== gatt) return@post
                if (statusCode == BluetoothGatt.GATT_SUCCESS) writeNext()
                else { writes.clear(); pendingId = null; onState("error · BLE write failed") }
            }
        }
    }

    fun start() {
        if (active) return
        if (!hasPermissions()) { onState("off · Bluetooth permissions needed"); return }
        if (adapter?.isEnabled != true) { onState("off · enable Bluetooth"); return }
        active = true
        app.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
        scan()
    }

    fun stop() {
        active = false
        stopScan()
        runCatching { app.unregisterReceiver(receiver) }
        val old = gatt; gatt = null
        runCatching { old?.disconnect(); old?.close() }
        command = null; status = null; verified = false; pendingId = null; lastStatus = null
        writes.clear(); decoder.reset(); handler.removeCallbacksAndMessages(null)
        onState("off")
    }

    fun currentStatus(): JSONObject? = lastStatus

    fun selectMode(mode: String): Boolean = send(BleProtocol.selectMode(mode))
    fun setConfig(mode: String, ssid: String, password: String): Boolean = runCatching {
        send(BleProtocol.setConfig(mode, ssid, password))
    }.getOrDefault(false)

    private fun send(json: JSONObject): Boolean {
        val g = gatt ?: return false
        if (!active || !verified || g.device.bondState != BluetoothDevice.BOND_BONDED || command == null) return false
        if (pendingId != null) return false
        val frame = BleProtocol.frame(json)
        pendingId = json.getString("requestId")
        val chunkSize = (mtu - 3).coerceAtLeast(20)
        for (offset in frame.indices step chunkSize) writes.addLast(frame.copyOfRange(offset, minOf(offset + chunkSize, frame.size)))
        onState("transferring · ${json.optString("mode")}")
        writeNext()
        val requested = pendingId
        handler.postDelayed({ if (pendingId == requested) { pendingId = null; onState("error · board response timeout") } }, 45_000)
        return true
    }

    private fun writeNext() {
        val g = gatt ?: return
        val characteristic = command ?: return
        if (writes.isEmpty()) return
        val bytes = writes.removeFirst()
        val result = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION") characteristic.value = bytes
            @Suppress("DEPRECATION") g.writeCharacteristic(characteristic)
        }
        if (!result) { writes.clear(); pendingId = null; onState("error · BLE write rejected") }
    }

    private fun receive(bytes: ByteArray) {
        handler.post {
            val messages = try { decoder.feed(bytes) } catch (_: Throwable) { onState("error · invalid BLE frame"); return@post }
            for (msg in messages) when (msg.optString("type")) {
                "status" -> {
                    val id = msg.optString("deviceId")
                    if (!DEVICE_ID.matches(id)) { onState("error · invalid device ID"); continue }
                    val saved = prefs.getString("bleDeviceId", null)
                    if (saved != null && saved != id) {
                        onState("error · different ESP32 device"); runCatching { gatt?.disconnect() }; continue
                    }
                    if (!verified) {
                        verified = true
                        prefs.edit().putString("bleDeviceId", id).putString("bleAddress", device?.address).apply()
                    }
                    lastStatus = msg
                    onState("connected · $id")
                    if (msg.optString("requestId") == pendingId &&
                        (msg.optString("phase") == "ready" || msg.optString("phase") == "error")) {
                        val success = msg.optString("phase") == "ready" &&
                            (msg.optString("mode") == "USB" || msg.optString("wifiState") == "connected")
                        onState(if (success) "connected · configuration ready" else "error · ${msg.optString("errorCode", "configuration failed")}")
                        pendingId = null
                    }
                    onBoardStatus(msg)
                }
                "ack" -> if (msg.optString("requestId") == pendingId) {
                    if (msg.optString("result") != "accepted") {
                        pendingId = null; onState("error · ${msg.optString("code", "rejected")}")
                    }
                }
            }
        }
    }

    private fun scan() {
        if (!active || scanning) return
        onState("searching")
        runCatching {
            adapter.bluetoothLeScanner.startScan(
                listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(BleProtocol.SERVICE)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
            scanning = true
        }.onFailure { onState("error · BLE scan unavailable"); reconnectLater() }
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        runCatching { adapter.bluetoothLeScanner.stopScan(scanCallback) }
    }

    private fun connectDevice(found: BluetoothDevice) {
        if (!active) return
        onState("connecting")
        runCatching { gatt = found.connectGatt(app, false, callback, BluetoothDevice.TRANSPORT_LE) }
            .onFailure { reconnectLater() }
    }

    private fun reconnectLater() {
        if (!active) return
        stopScan()
        val old = gatt; gatt = null
        verified = false; pendingId = null; lastStatus = null
        runCatching { old?.close() }
        handler.postDelayed({ if (active && gatt == null) scan() }, 3_000)
    }

    private fun hasPermissions(): Boolean = if (Build.VERSION.SDK_INT >= 31) {
        ContextCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    } else ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    companion object {
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val DEVICE_ID = Regex("[0-9A-F]{12}")
    }
}
