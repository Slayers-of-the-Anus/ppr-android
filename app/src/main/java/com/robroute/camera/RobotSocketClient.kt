package com.robroute.camera

import android.content.Context
import android.net.Network
import android.os.Handler
import android.os.Looper
import com.robroute.camera.network.NetworkPath
import com.robroute.camera.network.NetworkRouter
import com.robroute.camera.network.SecureSettings
import okhttp3.*
import org.json.JSONObject
import java.net.URLEncoder
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class RobotSocketClient(
    context: Context,
    private val webRtc: WebRtcController,
    private val onStatus: (String) -> Unit,
    private val onViewers: (List<ViewerInfo>, Int) -> Unit = { _, _ -> },
    private val onServoCommand: (ServoCommand) -> Unit = { },
    private val onConnectionChanged: (Boolean) -> Unit = { },
    private val onDriveCommand: (action: String, expiresAtElapsed: Long) -> Unit = { _, _ -> },
) {
    data class ServoCommand(val angle: Int, val action: String, val requestedBy: String)
    data class ViewerInfo(
        val username: String,
        val role: String,
        val viewerState: String,
        val mediaState: String,
        val signaling: String,
    )
    private data class QueuedSignal(val type: String, val sessionId: String, val text: String)

    private val main = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val generation = AtomicLong(0)
    private val router = NetworkRouter(context) { route, network -> switchRoute(route, network) }
    private val settings = SecureSettings(context)
    private val queueLock = Any()
    private val pendingCritical = LinkedHashMap<String, QueuedSignal>()
    private val pendingIce = ArrayDeque<QueuedSignal>()
    private var pendingCameraStatus: QueuedSignal? = null

    @Volatile private var socket: WebSocket? = null
    private var serverBase = ""
    private var currentRoute: NetworkPath? = null
    private var currentNetwork: Network? = null
    @Volatile private var ready = false
    private var cameraId = ""
    private var cameraName = ""
    @Volatile private var reconnect = false
    private var reconnectDelay = 500L
    private var maxViewers = 4
    private var serverClockOffset = 0L

    fun connect(base: String, id: String, name: String) {
        disconnectInternal(intentional = true, clearSettings = false)
        serverBase = normalizeBase(base)
        cameraId = id.trim()
        cameraName = name.trim().ifBlank { cameraId }
        reconnect = true
        reconnectDelay = 500L
        val public = settingsPublicUrl()
        if (public.isNotBlank() && settings.get("camera_secret") == null) {
            onStatus("server:public URL requires a 32-byte secret")
        }
        router.start(serverBase, if (settings.get("camera_secret") != null) public else "")
    }

    private fun settingsPublicUrl(): String = preferences.getString("publicServer", "")?.trim().orEmpty()
    private val preferences = context.applicationContext.getSharedPreferences("robroute", Context.MODE_PRIVATE)

    private fun switchRoute(route: NetworkPath?, network: Network?) {
        if (!reconnect) return
        if (route == currentRoute && network == currentNetwork && socket != null) return
        generation.incrementAndGet()
        ready = false
        val old = socket
        socket = null
        runCatching { old?.close(1000, "network-handover") }
        webRtc.closeAllSessions(false)
        synchronized(queueLock) { pendingCritical.clear(); pendingIce.clear(); pendingCameraStatus = null }
        onViewers(emptyList(), maxViewers)
        onConnectionChanged(false)
        currentRoute = route
        currentNetwork = network
        onStatus("networkPath:offline")
        if (route == null) onStatus("server:offline") else open()
    }

    fun disconnect() = disconnectInternal(intentional = true, clearSettings = false)

    fun shutdown() {
        disconnectInternal(intentional = true, clearSettings = true)
        router.shutdown()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        client.cache?.close()
    }

    private fun disconnectInternal(intentional: Boolean, clearSettings: Boolean) {
        reconnect = false
        router.stop()
        generation.incrementAndGet()
        ready = false
        main.removeCallbacksAndMessages(null)
        webRtc.closeAllSessions(false)
        synchronized(queueLock) {
            pendingCritical.clear(); pendingIce.clear(); pendingCameraStatus = null
        }
        val current = socket
        socket = null
        if (intentional) {
            try { current?.send(JSONObject().apply { put("type", "camera_disconnect") }.toString()) } catch (_: Throwable) { }
        }
        try { current?.close(1000, if (intentional) "client-disconnect" else "client-close") } catch (_: Throwable) { }
        onViewers(emptyList(), maxViewers)
        onStatus("server:disconnected")
        onStatus("networkPath:offline")
        onConnectionChanged(false)
        currentRoute = null; currentNetwork = null
        if (clearSettings) { serverBase = ""; cameraId = ""; cameraName = "" }
    }

    fun send(json: JSONObject) {
        val text = json.toString()
        val current = socket
        if (current != null && ready) {
            try { if (current.send(text)) return } catch (_: Throwable) { }
        }
        queueSignal(json, text)
    }

    fun publishCameraStatus() {
        val capture = webRtc.captureState()
        send(JSONObject().apply {
            put("type", "camera_status")
            put("available", capture.available)
            put("width", capture.width)
            put("height", capture.height)
            put("fps", capture.fps)
        })
    }

    fun publishServoState(available: Boolean, angle: Int, min: Int, max: Int) {
        send(JSONObject().apply {
            put("type", "servo_state")
            put("available", available)
            put("angle", angle)
            put("min", min)
            put("max", max)
        })
    }

    fun publishServoError(code: String, detail: String) {
        send(JSONObject().apply {
            put("type", "servo_error")
            put("code", code)
            put("detail", detail)
        })
    }

    fun publishDriveState(available: Boolean, direction: String) {
        send(JSONObject().apply {
            put("type", "drive_state"); put("available", available); put("direction", direction)
        })
    }

    private fun queueSignal(json: JSONObject, text: String) {
        val type = json.optString("type")
        val sid = json.optString("sessionId")
        val item = QueuedSignal(type, sid, text)
        synchronized(queueLock) {
            when (type) {
                "camera_status" -> pendingCameraStatus = item
                "webrtc_ice" -> {
                    if (pendingIce.size >= 128) pendingIce.removeFirst()
                    pendingIce.addLast(item)
                }
                "webrtc_stop" -> {
                    pendingCritical.entries.removeIf { it.value.sessionId == sid }
                    val keep = ArrayDeque<QueuedSignal>()
                    while (pendingIce.isNotEmpty()) pendingIce.removeFirst().let { if (it.sessionId != sid) keep.addLast(it) }
                    pendingIce.addAll(keep)
                    pendingCritical["stop:$sid"] = item
                }
                "webrtc_offer" -> pendingCritical["offer:$sid"] = item
                "webrtc_error" -> pendingCritical["error:$sid"] = item
                "webrtc_state" -> pendingCritical["state:$sid"] = item
                else -> pendingCritical["$type:$sid"] = item
            }
            // Never evict an offer/error/stop merely because ICE or repeated state updates arrived.
            while (pendingCritical.size > 48) {
                val removable = pendingCritical.keys.firstOrNull { it.startsWith("state:") }
                    ?: pendingCritical.keys.firstOrNull { !it.startsWith("offer:") && !it.startsWith("error:") && !it.startsWith("stop:") }
                    ?: break
                pendingCritical.remove(removable)
            }
        }
    }

    private fun open() {
        val route = currentRoute ?: return
        val network = currentNetwork ?: return
        if (!reconnect || cameraId.isBlank()) return
        val myGeneration = generation.incrementAndGet()
        val wsBase = when {
            route.base.startsWith("https://") -> "wss://${route.base.removePrefix("https://")}"
            route.base.startsWith("http://") -> "ws://${route.base.removePrefix("http://")}"
            route.base.startsWith("wss://") || route.base.startsWith("ws://") -> route.base
            else -> "ws://${route.base}"
        }
        val url = "$wsBase/ws/camera?cameraId=${enc(cameraId)}&name=${enc(cameraName)}"
        onStatus("server:connecting")

        val listener = object : WebSocketListener() {
            private var challenged = false
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!isCurrent(webSocket, myGeneration)) return
                reconnectDelay = 500L
                onStatus("server:authenticating")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!isCurrent(webSocket, myGeneration)) return
                val msg = try { JSONObject(text) } catch (_: Exception) { return }
                when (msg.optString("type")) {
                    "camera_auth_challenge" -> {
                        val secret = settings.get("camera_secret") ?: return
                        if (msg.optInt("v") != 2) return
                        challenged = true
                        val nonce = msg.optString("nonce")
                        val proof = cameraProof(secret, nonce, cameraId) ?: return
                        webSocket.send(JSONObject().put("type", "camera_auth").put("v", 2)
                            .put("cameraId", cameraId).put("proof", proof).toString())
                    }
                    "camera_ready", "robot_ready" -> {
                        serverClockOffset = msg.optLong("serverTime", System.currentTimeMillis()) - System.currentTimeMillis()
                        if (settings.get("camera_secret") != null && !challenged) {
                            webSocket.close(1008, "authentication required")
                            return
                        }
                        ready = true
                        maxViewers = msg.optInt("maxViewers", maxViewers).coerceIn(1, 8)
                        onStatus("server:connected")
                        webSocket.send(JSONObject().put("type", "network_state").put("v", 2)
                            .put("transport", route.transport).toString())
                        flushPending(webSocket)
                        publishCameraStatus()
                        onConnectionChanged(true)
                    }
                    "network_path" -> onStatus("networkPath:${msg.optString("path", "unknown")}")
                    "viewer_list" -> {
                        maxViewers = msg.optInt("max", maxViewers).coerceIn(1, 8)
                        val arr = msg.optJSONArray("viewers")
                        val viewers = mutableListOf<ViewerInfo>()
                        if (arr != null) for (i in 0 until arr.length()) {
                            val item = arr.optJSONObject(i) ?: continue
                            viewers += ViewerInfo(
                                username = item.optString("username", "viewer"),
                                role = item.optString("role", "relay"),
                                viewerState = item.optString("viewerState", "unknown"),
                                mediaState = item.optString("mediaState", "waiting"),
                                signaling = item.optString("signaling", "online"),
                            )
                        }
                        onViewers(viewers, maxViewers)
                    }
                    "servo_command" -> {
                        if (msg.has("angle")) {
                            onServoCommand(
                                ServoCommand(
                                    angle = msg.optInt("angle"),
                                    action = msg.optString("action"),
                                    requestedBy = msg.optString("requestedBy"),
                                ),
                            )
                        }
                    }
                    "drive_command" -> {
                        val action = msg.optString("action")
                        val remaining = msg.optLong("expiresAt", 0) - (System.currentTimeMillis() + serverClockOffset)
                        if (action == "stop") onDriveCommand("stop", android.os.SystemClock.elapsedRealtime() + 400)
                        else if (ready && action in setOf("forward", "backward") && remaining in 1..400) {
                            onDriveCommand(action, android.os.SystemClock.elapsedRealtime() + remaining)
                        }
                    }
                    "webrtc_start" -> {
                        val video = msg.optJSONObject("video")
                        webRtc.startSession(
                            msg.optString("sessionId"), msg.optJSONArray("iceServers"),
                            video?.optInt("width", 1280) ?: 1280,
                            video?.optInt("height", 720) ?: 720,
                            video?.optInt("fps", 30) ?: 30,
                        )
                    }
                    "webrtc_answer" -> webRtc.setAnswer(msg.optString("sessionId"), msg.optString("sdp"))
                    "webrtc_ice" -> msg.optJSONObject("candidate")?.let { webRtc.addIceCandidate(msg.optString("sessionId"), it) }
                    "webrtc_stop" -> webRtc.closeSessionIfMatches(msg.optString("sessionId"), false)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                disconnected(webSocket, myGeneration, "closed $code${if (reason.isNotBlank()) " ($reason)" else ""}")
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                disconnected(webSocket, myGeneration, "error ${t.message ?: t.javaClass.simpleName}")
            }
        }

        val boundClient = client.newBuilder()
            .socketFactory(network.socketFactory)
            .dns(object : Dns { override fun lookup(hostname: String): List<InetAddress> = network.getAllByName(hostname).toList() })
            .build()
        val newSocket = try { boundClient.newWebSocket(Request.Builder().url(url).build(), listener) } catch (e: Throwable) {
            onStatus("server:error ${e.message ?: e.javaClass.simpleName}; reconnecting")
            scheduleReconnect(myGeneration)
            return
        }
        socket = newSocket
    }

    private fun isCurrent(ws: WebSocket, myGeneration: Long): Boolean = reconnect && generation.get() == myGeneration && socket === ws

    private fun flushPending(webSocket: WebSocket) {
        val snapshot = mutableListOf<QueuedSignal>()
        synchronized(queueLock) {
            pendingCameraStatus?.let(snapshot::add); pendingCameraStatus = null
            snapshot += pendingCritical.values.filter { it.type == "webrtc_offer" }
            snapshot += pendingCritical.values.filter { it.type != "webrtc_offer" }
            pendingCritical.clear()
            while (pendingIce.isNotEmpty()) snapshot += pendingIce.removeFirst()
        }
        for (index in snapshot.indices) {
            val item = snapshot[index]
            val sent = try { webSocket.send(item.text) } catch (_: Throwable) { false }
            if (sent) continue
            synchronized(queueLock) { for (i in index until snapshot.size) requeueLocked(snapshot[i]) }
            return
        }
    }

    private fun requeueLocked(item: QueuedSignal) {
        when (item.type) {
            "camera_status" -> pendingCameraStatus = item
            "webrtc_ice" -> { if (pendingIce.size >= 128) pendingIce.removeFirst(); pendingIce.addLast(item) }
            "webrtc_stop" -> pendingCritical["stop:${item.sessionId}"] = item
            "webrtc_offer" -> pendingCritical["offer:${item.sessionId}"] = item
            "webrtc_error" -> pendingCritical["error:${item.sessionId}"] = item
            "webrtc_state" -> pendingCritical["state:${item.sessionId}"] = item
            else -> pendingCritical["${item.type}:${item.sessionId}"] = item
        }
    }

    private fun disconnected(webSocket: WebSocket, myGeneration: Long, detail: String) {
        if (generation.get() != myGeneration || socket !== webSocket) return
        socket = null
        ready = false
        webRtc.closeAllSessions(false)
        onStatus("networkPath:offline")
        onStatus("server:$detail; reconnecting")
        onConnectionChanged(false)
        scheduleReconnect(myGeneration)
    }

    private fun scheduleReconnect(myGeneration: Long) {
        if (!reconnect || generation.get() != myGeneration) return
        val delay = reconnectDelay
        reconnectDelay = (reconnectDelay * 2).coerceAtMost(8000L)
        main.postDelayed({ if (reconnect && socket == null && generation.get() == myGeneration) { router.retry(); open() } }, delay)
    }

    private fun normalizeBase(base: String): String = base.trim().removeSuffix("/")
    private fun enc(v: String) = URLEncoder.encode(v, StandardCharsets.UTF_8.toString())

    companion object {
        fun cameraProof(encodedSecret: String, nonce: String, cameraId: String): String? = runCatching {
            val key = Base64.getUrlDecoder().decode(encodedSecret)
            require(key.size == 32)
            val rawNonce = Base64.getUrlDecoder().decode(nonce)
            require(rawNonce.size == 16)
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            Base64.getUrlEncoder().withoutPadding().encodeToString(
                mac.doFinal("$nonce\n$cameraId".toByteArray(Charsets.UTF_8)))
        }.getOrNull()
    }
}
