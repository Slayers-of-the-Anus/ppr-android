package com.robroute.camera

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.os.IBinder
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.robroute.camera.databinding.ActivityMainBinding
import com.robroute.camera.hotspot.HotspotSnapshot
import com.robroute.camera.servo.ServoTransportMode

class MainActivity : AppCompatActivity(), CameraStreamService.UiListener {
    private lateinit var binding: ActivityMainBinding
    private var service: CameraStreamService? = null
    private var bound = false
    private val status = linkedMapOf(
        "server" to "disconnected", "camera" to "starting", "webrtc" to "idle",
        "esp32" to "disconnected", "servo" to "—", "esp32Link" to "USB cable",
        "esp32Usb" to "disconnected", "esp32Network" to "searching",
    )
    private var viewersText = "Viewers: 0/4 · nobody connected"
    private var pendingConnect: Triple<String, String, String>? = null
    private var pendingHotspotStart = false
    private var hotspotSnapshot = HotspotSnapshot()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? CameraStreamService.LocalBinder)?.service()
            bound = service != null
            service?.setUiListener(this@MainActivity)
            service?.startPreviewCamera()
            service?.attachPreview(binding.preview)
            if (pendingHotspotStart) {
                pendingHotspotStart = false
                service?.startLocalHotspot()
            }
            pendingConnect?.let { (serverUrl, id, cameraName) ->
                pendingConnect = null
                ContextCompat.startForegroundService(this@MainActivity, Intent(this@MainActivity, CameraStreamService::class.java))
                service?.connect(serverUrl, id, cameraName)
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            service = null
            status["server"] = "service disconnected"
            renderStatus()
        }
    }

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) bindCameraService() else {
            status["camera"] = "permission denied"
            renderStatus()
        }
    }

    private val hotspotPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startHotspotWithService()
        else {
            status["hotspot"] = "permission denied"
            renderStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences("robroute", MODE_PRIVATE)
        binding.serverUrl.setText(prefs.getString("server", "http://192.168.0.128:8080"))
        binding.robotId.setText(prefs.getString("robotId", "phone_01"))
        binding.robotName.setText(prefs.getString("robotName", "Courier Android"))
        val savedTransport = prefs.getString("servoTransport", ServoTransportMode.USB.name)
        binding.transportModeGroup.check(
            if (savedTransport == ServoTransportMode.WIFI.name) binding.wifiTransportButton.id
            else binding.usbTransportButton.id,
        )

        binding.connectButton.setOnClickListener {
            val serverUrl = binding.serverUrl.text.toString().trim()
            val id = binding.robotId.text.toString().trim()
            val cameraName = binding.robotName.text.toString().trim()
            if (serverUrl.isBlank() || id.length < 2) {
                status["server"] = "check settings"
                renderStatus()
                return@setOnClickListener
            }
            prefs.edit().putString("server", serverUrl).putString("robotId", id).putString("robotName", cameraName).apply()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                pendingConnect = Triple(serverUrl, id, cameraName)
                status["camera"] = "permission required"
                renderStatus()
                cameraPermission.launch(Manifest.permission.CAMERA)
                return@setOnClickListener
            }
            val currentService = service
            if (currentService == null) {
                pendingConnect = Triple(serverUrl, id, cameraName)
                status["server"] = "starting service"
                renderStatus()
                bindCameraService()
                return@setOnClickListener
            }
            ContextCompat.startForegroundService(this, Intent(this, CameraStreamService::class.java))
            currentService.connect(serverUrl, id, cameraName)
        }
        binding.disconnectButton.setOnClickListener { service?.disconnectCamera() }
        binding.switchCameraButton.setOnClickListener { service?.switchCamera() }
        binding.hotspotButton.setOnClickListener {
            if (hotspotSnapshot.state == HotspotSnapshot.State.RUNNING || hotspotSnapshot.state == HotspotSnapshot.State.STARTING) {
                service?.stopLocalHotspot()
            } else {
                requestHotspotPermissionAndStart()
            }
        }
        binding.sendWifiCredentialsButton.setOnClickListener {
            val sent = service?.sendHotspotCredentialsToEsp32() == true
            status["esp32Wifi"] = if (sent) "sending credentials over USB" else status["esp32Wifi"] ?: "not sent"
            renderStatus()
        }
        binding.transportModeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = if (checkedId == binding.wifiTransportButton.id) ServoTransportMode.WIFI else ServoTransportMode.USB
            prefs.edit().putString("servoTransport", mode.name).apply()
            service?.setServoTransportMode(mode)
            status["esp32Link"] = if (mode == ServoTransportMode.USB) "USB cable" else "Wi-Fi hotspot"
            renderStatus()
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) bindCameraService()
        else cameraPermission.launch(Manifest.permission.CAMERA)
        renderStatus()
    }

    private fun bindCameraService() {
        if (bound) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        bindService(Intent(this, CameraStreamService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStatus(status: Map<String, String>) {
        runOnUiThread {
            this.status.putAll(status)
            renderStatus()
        }
    }

    override fun onViewers(viewers: List<RobotSocketClient.ViewerInfo>, max: Int) {
        runOnUiThread {
            viewersText = if (viewers.isEmpty()) {
                "Viewers: 0/$max · nobody connected"
            } else {
                val names = viewers.joinToString(", ") { viewer ->
                    val marker = when (viewer.mediaState) { "live" -> "●"; "stalled" -> "!"; else -> "○" }
                    val role = if (viewer.role == "source") "source" else "relay"
                    "$marker ${viewer.username} [$role]"
                }
                "Viewers: ${viewers.size}/$max · $names"
            }
            binding.viewers.text = viewersText
        }
    }

    override fun onHotspot(snapshot: HotspotSnapshot) {
        runOnUiThread {
            hotspotSnapshot = snapshot
            binding.hotspotButton.text = when (snapshot.state) {
                HotspotSnapshot.State.RUNNING -> "STOP ESP32 HOTSPOT"
                HotspotSnapshot.State.STARTING -> "STARTING HOTSPOT…"
                else -> "START ESP32 HOTSPOT"
            }
            binding.hotspotButton.isEnabled = snapshot.state != HotspotSnapshot.State.STARTING
            binding.sendWifiCredentialsButton.isEnabled = snapshot.state == HotspotSnapshot.State.RUNNING
            binding.hotspotCredentials.text = when (snapshot.state) {
                HotspotSnapshot.State.RUNNING -> "SSID: ${snapshot.ssid}\nPassword: ${snapshot.password}\n${snapshot.detail}"
                HotspotSnapshot.State.ERROR -> "Hotspot error: ${snapshot.detail}"
                HotspotSnapshot.State.STARTING -> "Hotspot: starting…"
                HotspotSnapshot.State.STOPPED -> "Hotspot: stopped${if (snapshot.detail.isNotBlank()) " · ${snapshot.detail}" else ""}"
            }
        }
    }

    private fun requestHotspotPermissionAndStart() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            startHotspotWithService()
        } else hotspotPermission.launch(permission)
    }

    private fun startHotspotWithService() {
        val currentService = service
        if (currentService != null) {
            currentService.startLocalHotspot()
            return
        }
        pendingHotspotStart = true
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            bindCameraService()
        } else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun renderStatus() {
        binding.status.text = buildString {
            appendLine("Server: ${status["server"]}")
            appendLine("Camera: ${status["camera"]}")
            appendLine("WebRTC: ${status["webrtc"]}")
            appendLine("ESP32: ${status["esp32"]}")
            appendLine("Servo: ${status["servo"]}")
            appendLine("Control link: ${status["esp32Link"] ?: "USB cable"}")
            appendLine("USB: ${status["esp32Usb"] ?: "disconnected"}")
            appendLine("Wi-Fi link: ${status["esp32Network"] ?: "searching"}")
            appendLine("ESP32 Wi-Fi: ${status["esp32Wifi"] ?: "not configured"}")
            append("Hotspot: ${status["hotspot"] ?: "stopped"}")
        }
        binding.viewers.text = viewersText
    }

    override fun onStop() {
        service?.detachPreview(binding.preview)
        service?.setUiListener(null)
        if (bound) {
            try { unbindService(connection) } catch (_: Exception) { }
            bound = false
            service = null
        }
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) bindCameraService()
    }
}
