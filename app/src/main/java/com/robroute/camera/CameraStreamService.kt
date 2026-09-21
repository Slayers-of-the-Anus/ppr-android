package com.robroute.camera

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.robroute.camera.servo.ServoController
import com.robroute.camera.servo.ServoTransportMode
import com.robroute.camera.hotspot.HotspotSnapshot
import com.robroute.camera.hotspot.LocalHotspotController
import org.webrtc.SurfaceViewRenderer
import java.util.concurrent.ConcurrentHashMap

class CameraStreamService : Service() {
    interface UiListener {
        fun onStatus(status: Map<String, String>)
        fun onViewers(viewers: List<RobotSocketClient.ViewerInfo>, max: Int)
        fun onHotspot(snapshot: HotspotSnapshot)
    }

    inner class LocalBinder : Binder() { fun service(): CameraStreamService = this@CameraStreamService }
    private val binder = LocalBinder()
    private lateinit var webRtc: WebRtcController
    private lateinit var socket: RobotSocketClient
    private lateinit var servoController: ServoController
    private lateinit var hotspotController: LocalHotspotController
    private val status = ConcurrentHashMap<String, String>().apply {
        put("server", "disconnected"); put("camera", "starting"); put("webrtc", "idle")
        put("esp32", "disconnected"); put("servo", "—"); put("esp32Wifi", "not configured"); put("hotspot", "stopped")
        put("esp32Link", "USB cable"); put("esp32Usb", "disconnected"); put("esp32Network", "searching")
    }
    @Volatile private var listener: UiListener? = null
    private var lastViewers: List<RobotSocketClient.ViewerInfo> = emptyList()
    private var lastMaxViewers = 4
    private var wakeLock: PowerManager.WakeLock? = null
    private var foregroundStarted = false
    private var connectionRequested = false

    override fun onCreate() {
        super.onCreate()
        webRtc = WebRtcController(
            applicationContext,
            sendSignal = { json -> if (::socket.isInitialized) socket.send(json) },
            onStatus = ::updateStatus,
            onCaptureStateChanged = { if (::socket.isInitialized) socket.publishCameraStatus() },
        )
        val savedMode = getSharedPreferences("robroute", MODE_PRIVATE)
            .getString("servoTransport", ServoTransportMode.USB.name)
            ?.let { runCatching { ServoTransportMode.valueOf(it) }.getOrNull() }
            ?: ServoTransportMode.USB
        servoController = ServoController(
            applicationContext,
            initialMode = savedMode,
            onStatus = ::updateStatus,
            onState = { available, angle, min, max ->
                if (::socket.isInitialized) socket.publishServoState(available, angle, min, max)
            },
            onError = { code, detail ->
                if (::socket.isInitialized) socket.publishServoError(code, detail)
            },
        )
        socket = RobotSocketClient(
            webRtc, ::updateStatus, ::updateViewers,
            onServoCommand = { command -> servoController.moveTo(command.angle) },
            onConnectionChanged = { connected -> if (connected) servoController.publishSnapshot() },
        )
        hotspotController = LocalHotspotController(
            applicationContext,
            onState = { snapshot ->
                updateStatus("hotspot:${snapshot.state.name.lowercase()}${if (snapshot.detail.isNotBlank()) " · ${snapshot.detail}" else ""}")
                listener?.onHotspot(snapshot)
            },
        )
        servoController.start()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()
        webRtc.startCamera(1280, 720, 30)
        if (!connectionRequested) {
            val prefs = getSharedPreferences("robroute", MODE_PRIVATE)
            if (prefs.getBoolean("autoConnect", false)) {
                val server = prefs.getString("server", "")?.trim().orEmpty()
                val id = prefs.getString("robotId", "phone_01")?.trim().orEmpty()
                val name = prefs.getString("robotName", "Courier Android")?.trim().orEmpty()
                if (server.isNotBlank() && id.length >= 2) {
                    connectionRequested = true
                    acquireWakeLock()
                    socket.connect(server, id, name)
                }
            }
        }
        return START_STICKY
    }

    fun setUiListener(value: UiListener?) {
        listener = value
        value?.onStatus(status.toMap())
        value?.onViewers(lastViewers, lastMaxViewers)
        if (::hotspotController.isInitialized) value?.onHotspot(hotspotController.current())
    }

    fun startPreviewCamera() = webRtc.startCamera(1280, 720, 30)
    fun attachPreview(preview: SurfaceViewRenderer) = webRtc.attachPreview(preview)
    fun detachPreview(preview: SurfaceViewRenderer) = webRtc.detachPreview(preview)

    fun connect(server: String, id: String, name: String) {
        ensureForeground()
        webRtc.startCamera(1280, 720, 30)
        connectionRequested = true
        getSharedPreferences("robroute", MODE_PRIVATE).edit().putBoolean("autoConnect", true).apply()
        acquireWakeLock()
        socket.connect(server, id, name)
    }

    fun disconnectCamera() {
        connectionRequested = false
        getSharedPreferences("robroute", MODE_PRIVATE).edit().putBoolean("autoConnect", false).apply()
        socket.disconnect()
        releaseWakeLock()
        stopForegroundCompat()
        stopSelf()
    }

    fun switchCamera() = webRtc.switchCamera()
    fun startLocalHotspot() = hotspotController.start()
    fun stopLocalHotspot() = hotspotController.stop()

    fun sendHotspotCredentialsToEsp32(): Boolean {
        val hotspot = hotspotController.current()
        if (hotspot.state != HotspotSnapshot.State.RUNNING || hotspot.ssid.isBlank() || hotspot.password.isBlank()) {
            updateStatus("esp32Wifi:start the phone hotspot first")
            return false
        }
        return servoController.configureWifi(hotspot.ssid, hotspot.password)
    }

    fun sendBoardConfigurationOverUsb(mode: String, ssid: String, password: String): Boolean =
        servoController.configureMode(mode, ssid, password)

    fun setServoTransportMode(mode: ServoTransportMode) {
        getSharedPreferences("robroute", MODE_PRIVATE).edit().putString("servoTransport", mode.name).apply()
        servoController.setTransportMode(mode)
    }

    fun servoTransportMode(): ServoTransportMode = servoController.transportMode()

    fun cameraState(): WebRtcController.CaptureState = webRtc.captureState()

    private fun updateStatus(line: String) {
        val parts = line.split(':', limit = 2)
        if (parts.size == 2) status[parts[0]] = parts[1]
        listener?.onStatus(status.toMap())
        if (foregroundStarted) updateNotification()
    }

    private fun updateViewers(viewers: List<RobotSocketClient.ViewerInfo>, max: Int) {
        lastViewers = viewers
        lastMaxViewers = max
        listener?.onViewers(viewers, max)
        if (foregroundStarted) updateNotification()
    }

    private fun ensureForeground() {
        if (foregroundStarted) return
        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foregroundStarted = true
    }

    private fun stopForegroundCompat() {
        if (!foregroundStarted) return
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        foregroundStarted = false
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "PhonePilot Camera", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Keeps the robot camera stream active"
            setShowBadge(false)
        })
    }

    private fun buildNotification(): Notification {
        val launch = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val viewers = if (lastViewers.isEmpty()) "no viewers" else "${lastViewers.size}/$lastMaxViewers viewers"
        val server = status["server"] ?: "disconnected"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("PhonePilot Camera")
            .setContentText("$server · $viewers")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pending)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RoboRoute:CameraStream").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Throwable) { }
        wakeLock = null
    }

    override fun onDestroy() {
        listener = null
        releaseWakeLock()
        try { socket.disconnect() } catch (_: Throwable) { }
        try { servoController.stop() } catch (_: Throwable) { }
        try { hotspotController.stop() } catch (_: Throwable) { }
        try { webRtc.release() } catch (_: Throwable) { }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "robroute_camera_stream"
        private const val NOTIFICATION_ID = 7001
    }
}
