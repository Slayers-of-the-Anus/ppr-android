package com.robroute.camera

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.robroute.camera.databinding.ActivityMainBinding
import com.robroute.camera.hotspot.HotspotSnapshot
import com.robroute.camera.hotspot.EspSetupProvisioner
import com.robroute.camera.servo.ServoTransportMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), CameraStreamService.UiListener {
    private lateinit var binding: ActivityMainBinding
    private var service: CameraStreamService? = null
    private var bound = false
    private var robotModeEnabled = true
    private val eventLines = mutableListOf<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val status = linkedMapOf(
        "server" to "disconnected", "camera" to "starting", "webrtc" to "idle",
        "esp32" to "disconnected", "servo" to "—", "esp32Link" to "USB cable",
        "esp32Usb" to "disconnected", "esp32Network" to "searching",
    )
    private var viewersText = "Viewers: 0/4 · nobody connected"
    private var viewerCount = 0
    private var viewerLimit = 4
    private var pendingConnect: Triple<String, String, String>? = null
    private var pendingHotspotStart = false
    private var hotspotSnapshot = HotspotSnapshot()
    private lateinit var setupProvisioner: EspSetupProvisioner

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
            updateLocalStatus("server", "service disconnected")
        }
    }

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) bindCameraService() else updateLocalStatus("camera", "permission denied")
    }

    private val hotspotPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startHotspotWithService()
        else updateLocalStatus("hotspot", "permission denied")
    }

    private val networkNamePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) fillCurrentWifiName() else showConfigurationResult("Location permission is needed to read the Wi-Fi name")
    }

    private val setupNetworkPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.all { it }) sendBoardConfiguration()
        else showConfigurationResult("Allow Nearby Wi-Fi and Location access to connect to ESP32 setup Wi-Fi")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val prefs = getSharedPreferences("robroute", MODE_PRIVATE)
        robotModeEnabled = prefs.getBoolean(PREF_ROBOT_MODE, true)
        enforceOrientation(robotModeEnabled)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupProvisioner = EspSetupProvisioner(this)
        savedInstanceState?.getStringArrayList(STATE_EVENT_LOG)?.let(eventLines::addAll)
        if (eventLines.isEmpty()) appendEvent("PhonePilot interface ready")

        binding.serverUrl.setText(prefs.getString("server", "http://192.168.0.128:8080"))
        binding.robotId.setText(prefs.getString("robotId", "phone_01"))
        binding.robotName.setText(prefs.getString("robotName", "Courier Android"))
        val savedTransport = prefs.getString("servoTransport", ServoTransportMode.USB.name)
        binding.transportModeGroup.check(
            when (savedTransport) {
                ServoTransportMode.WIFI.name -> binding.wifiTransportButton.id
                ServoTransportMode.SHARED.name -> binding.sharedTransportButton.id
                else -> binding.usbTransportButton.id
            },
        )

        binding.robotSettingsButton.setOnClickListener { switchMode(robotMode = false) }
        binding.setupRobotModeButton.setOnClickListener { switchMode(robotMode = true) }

        binding.connectButton.setOnClickListener {
            val serverUrl = binding.serverUrl.text.toString().trim()
            val id = binding.robotId.text.toString().trim()
            val cameraName = binding.robotName.text.toString().trim()
            if (serverUrl.isBlank() || id.length < 2) {
                updateLocalStatus("server", "check settings")
                return@setOnClickListener
            }
            prefs.edit().putString("server", serverUrl).putString("robotId", id).putString("robotName", cameraName).apply()
            binding.robotIdentity.text = "$id  ·  $cameraName"
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                pendingConnect = Triple(serverUrl, id, cameraName)
                updateLocalStatus("camera", "permission required")
                cameraPermission.launch(Manifest.permission.CAMERA)
                return@setOnClickListener
            }
            val currentService = service
            if (currentService == null) {
                pendingConnect = Triple(serverUrl, id, cameraName)
                updateLocalStatus("server", "starting service")
                bindCameraService()
                return@setOnClickListener
            }
            ContextCompat.startForegroundService(this, Intent(this, CameraStreamService::class.java))
            currentService.connect(serverUrl, id, cameraName)
        }
        binding.disconnectButton.setOnClickListener { service?.disconnectCamera() }
        binding.switchCameraButton.setOnClickListener {
            service?.switchCamera()
            appendEvent("Camera switched")
        }
        binding.hotspotButton.setOnClickListener {
            if (hotspotSnapshot.state == HotspotSnapshot.State.RUNNING || hotspotSnapshot.state == HotspotSnapshot.State.STARTING) {
                service?.stopLocalHotspot()
            } else {
                requestHotspotPermissionAndStart()
            }
        }
        binding.sendWifiCredentialsButton.setOnClickListener {
            val sent = service?.sendHotspotCredentialsToEsp32() == true
            updateLocalStatus("esp32Wifi", if (sent) "sending credentials over USB" else status["esp32Wifi"] ?: "not sent")
        }
        binding.transportModeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                binding.wifiTransportButton.id -> ServoTransportMode.WIFI
                binding.sharedTransportButton.id -> ServoTransportMode.SHARED
                else -> ServoTransportMode.USB
            }
            prefs.edit().putString("servoTransport", mode.name).apply()
            service?.setServoTransportMode(mode)
            updateLocalStatus("esp32Link", when (mode) {
                ServoTransportMode.USB -> "USB cable"
                ServoTransportMode.WIFI -> "Phone hotspot"
                ServoTransportMode.SHARED -> "Shared Wi-Fi"
            })
        }
        binding.readCurrentNetworkButton.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                fillCurrentWifiName()
            } else networkNamePermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        binding.sendBoardConfigurationButton.setOnClickListener { sendBoardConfiguration() }

        applyUiMode()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) bindCameraService()
        else cameraPermission.launch(Manifest.permission.CAMERA)
        renderStatus()
    }

    private fun switchMode(robotMode: Boolean) {
        robotModeEnabled = robotMode
        getSharedPreferences("robroute", MODE_PRIVATE).edit().putBoolean(PREF_ROBOT_MODE, robotMode).apply()
        appendEvent(if (robotMode) "Robot mode enabled" else "Setup mode enabled")
        applyUiMode()
        requestedOrientation = if (robotMode) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
    }

    private fun applyUiMode() {
        binding.robotMode.visibility = if (robotModeEnabled) View.VISIBLE else View.GONE
        binding.setupMode.visibility = if (robotModeEnabled) View.GONE else View.VISIBLE
        if (robotModeEnabled) {
            currentFocus?.clearFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(binding.root.windowToken, 0)
        }
        binding.robotIdentity.text = "${binding.robotId.text}  ·  ${binding.robotName.text}"
    }

    private fun enforceOrientation(robotMode: Boolean) {
        val orientation = resources.configuration.orientation
        val alreadyCorrect = if (robotMode) {
            orientation == Configuration.ORIENTATION_LANDSCAPE
        } else {
            orientation == Configuration.ORIENTATION_PORTRAIT
        }
        if (!alreadyCorrect) {
            requestedOrientation = if (robotMode) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
        }
    }

    private fun bindCameraService() {
        if (bound) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        bindService(Intent(this, CameraStreamService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStatus(status: Map<String, String>) {
        runOnUiThread {
            status.forEach { (key, value) ->
                if (this.status[key] != value) appendEvent("${statusLabel(key)}: $value")
            }
            this.status.putAll(status)
            renderStatus()
        }
    }

    override fun onViewers(viewers: List<RobotSocketClient.ViewerInfo>, max: Int) {
        runOnUiThread {
            viewerCount = viewers.size
            viewerLimit = max
            val updatedText = if (viewers.isEmpty()) {
                "Viewers: 0/$max · nobody connected"
            } else {
                val names = viewers.joinToString(", ") { viewer ->
                    val marker = when (viewer.mediaState) { "live" -> "●"; "stalled" -> "!"; else -> "○" }
                    val role = if (viewer.role == "source") "source" else "relay"
                    "$marker ${viewer.username} [$role]"
                }
                "Viewers: ${viewers.size}/$max · $names"
            }
            if (viewersText != updatedText) appendEvent(updatedText)
            viewersText = updatedText
            renderStatus()
        }
    }

    override fun onHotspot(snapshot: HotspotSnapshot) {
        runOnUiThread {
            val previousState = hotspotSnapshot.state
            hotspotSnapshot = snapshot
            if (previousState != snapshot.state) appendEvent("Hotspot: ${snapshot.state.name.lowercase()}")
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
            renderStatus()
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

    private fun fillCurrentWifiName() {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            connectivity.allNetworks.asSequence()
                .mapNotNull(connectivity::getNetworkCapabilities)
                .filter { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
                .mapNotNull { (it.transportInfo as? WifiInfo)?.ssid }
                .firstOrNull { it.isNotBlank() && it != WifiManager.UNKNOWN_SSID }
        } else null
        @Suppress("DEPRECATION")
        val fallback = (getSystemService(WIFI_SERVICE) as WifiManager).connectionInfo.ssid
        val networkName = (ssid ?: fallback)?.removeSurrounding("\"")
        if (networkName.isNullOrBlank() || networkName == WifiManager.UNKNOWN_SSID) {
            showConfigurationResult("No Wi-Fi name available. Connect phone to the shared network and enable Location, or enter SSID manually.")
            return
        }
        binding.sharedWifiSsid.setText(networkName)
        showConfigurationResult("Current Wi-Fi name filled in. Enter its password; Android cannot reveal a saved password.")
    }

    private fun sendBoardConfiguration() {
        val mode = when (binding.boardModeGroup.checkedRadioButtonId) {
            binding.boardUsbMode.id -> "USB"
            binding.boardHotspotMode.id -> "HOTSPOT"
            else -> "SHARED"
        }
        val ssid = when (mode) {
            "SHARED" -> binding.sharedWifiSsid.text.toString().trim()
            "HOTSPOT" -> hotspotSnapshot.ssid
            else -> ""
        }
        val password = when (mode) {
            "SHARED" -> binding.sharedWifiPassword.text.toString()
            "HOTSPOT" -> hotspotSnapshot.password
            else -> ""
        }
        if (mode != "USB" && (ssid.toByteArray(Charsets.UTF_8).size !in 1..32 || password.length !in 8..63)) {
            showConfigurationResult("Enter a valid network name and password (8–63 characters), or start the phone hotspot.")
            return
        }
        if (binding.provisionUsb.isChecked) {
            val sent = service?.sendBoardConfigurationOverUsb(mode, ssid, password) == true
            showConfigurationResult(if (sent) "Sending $mode configuration over USB; wait for ESP32 confirmation." else "Connect ESP32 by USB and grant access first.")
            return
        }
        if (mode == "HOTSPOT") {
            showConfigurationResult("For the phone's own hotspot, use USB provisioning; Android cannot reliably join ESP32 setup Wi-Fi while hosting its hotspot.")
            return
        }
        val requiredPermissions = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            setupNetworkPermissions.launch(missingPermissions.toTypedArray())
            return
        }
        val setupSsid = binding.espSetupSsid.text.toString().trim()
        val setupPassword = binding.espSetupPassword.text.toString()
        if (!setupSsid.startsWith("PhonePilot-Setup-") || setupPassword.length !in 8..63) {
            showConfigurationResult("Enter the ESP32 setup SSID and password shown in Arduino Serial Monitor.")
            return
        }
        binding.sendBoardConfigurationButton.isEnabled = false
        showConfigurationResult("Waiting for Android approval to join ESP32 setup Wi-Fi…")
        setupProvisioner.send(setupSsid, setupPassword, mode, ssid, password) { success, detail ->
            binding.sendBoardConfigurationButton.isEnabled = true
            showConfigurationResult(detail)
            if (success) appendEvent("ESP32 $mode configuration saved")
        }
    }

    private fun showConfigurationResult(detail: String) {
        binding.boardConfigurationResult.text = detail
    }

    private fun updateLocalStatus(key: String, value: String) {
        if (status[key] != value) appendEvent("${statusLabel(key)}: $value")
        status[key] = value
        renderStatus()
    }

    private fun renderStatus() {
        val server = status["server"] ?: "disconnected"
        val camera = status["camera"] ?: "starting"
        val webRtc = status["webrtc"] ?: "idle"
        val esp32 = status["esp32"] ?: "disconnected"
        val controlLink = status["esp32Link"] ?: "USB cable"
        val hotspot = status["hotspot"] ?: hotspotSnapshot.state.name.lowercase()

        binding.status.text = buildString {
            appendLine("Server      ${server}")
            appendLine("Camera      ${camera}")
            appendLine("WebRTC      ${webRtc}")
            appendLine("ESP32       ${esp32}")
            appendLine("Servo       ${status["servo"]}")
            appendLine("Control     $controlLink")
            appendLine("USB         ${status["esp32Usb"] ?: "disconnected"}")
            appendLine("Wi-Fi link  ${status["esp32Network"] ?: "searching"}")
            appendLine("ESP32 Wi-Fi ${status["esp32Wifi"] ?: "not configured"}")
            append("Hotspot     $hotspot")
        }
        binding.viewers.text = viewersText

        setTelemetry(binding.serverState, "SERVER", server, isHealthy(server))
        setTelemetry(binding.boardState, "CONTROL BOARD", "$esp32  ·  $controlLink", isHealthy(esp32))
        setTelemetry(binding.cameraState, "CAMERA / STREAM", "$camera  ·  $webRtc", isHealthy(camera))
        setTelemetry(binding.viewerState, "VIEWERS", "$viewerCount / $viewerLimit", viewerCount > 0)
        binding.robotDetails.text = "Control  $controlLink     •     USB  ${status["esp32Usb"] ?: "disconnected"}     •     Wi-Fi  ${status["esp32Network"] ?: "searching"}     •     Hotspot  $hotspot"

        val coreHealthy = isHealthy(server) && isHealthy(camera) && isHealthy(esp32)
        binding.robotHealth.text = if (coreHealthy) "●  SYSTEM READY" else "●  ATTENTION"
        binding.robotHealth.setTextColor(ContextCompat.getColor(this, if (coreHealthy) R.color.success else R.color.warning))
        binding.eventLog.text = eventLines.joinToString("\n")
    }

    private fun setTelemetry(view: TextView, label: String, value: String, healthy: Boolean) {
        view.text = "$label\n${if (healthy) "●" else "○"}  ${displayValue(value)}"
        view.setTextColor(ContextCompat.getColor(this, if (healthy) R.color.text_primary else R.color.text_secondary))
    }

    private fun isHealthy(value: String): Boolean {
        val normalized = value.lowercase(Locale.ROOT)
        if (normalized.contains("disconnected") || normalized.contains("error") || normalized.contains("denied")) return false
        return CAMERA_FORMAT.matches(normalized) || normalized == "connected" || normalized.startsWith("connected ") ||
            normalized.contains("running") || normalized.contains("started") ||
            normalized.contains("streaming") || normalized.contains("capturing") || normalized.contains("ready")
    }

    private fun displayValue(value: String): String = value.replaceFirstChar {
        if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
    }

    private fun appendEvent(message: String) {
        val line = "${timeFormat.format(Date())}  $message"
        eventLines.add(line)
        while (eventLines.size > MAX_EVENT_LINES) eventLines.removeAt(0)
        if (::binding.isInitialized) binding.eventLog.text = eventLines.joinToString("\n")
    }

    private fun statusLabel(key: String): String = when (key) {
        "server" -> "Server"
        "camera" -> "Camera"
        "webrtc" -> "WebRTC"
        "esp32" -> "ESP32"
        "servo" -> "Servo"
        "esp32Link" -> "Control link"
        "esp32Usb" -> "USB"
        "esp32Network" -> "Wi-Fi link"
        "esp32Wifi" -> "ESP32 Wi-Fi"
        "hotspot" -> "Hotspot"
        else -> key
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList(STATE_EVENT_LOG, ArrayList(eventLines))
        super.onSaveInstanceState(outState)
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

    override fun onDestroy() {
        setupProvisioner.cancel()
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) bindCameraService()
    }

    companion object {
        private const val PREF_ROBOT_MODE = "robotMode"
        private const val STATE_EVENT_LOG = "eventLog"
        private const val MAX_EVENT_LINES = 18
        private val CAMERA_FORMAT = Regex("\\d+x\\d+@\\d+")
    }
}
