package com.robroute.camera

import android.os.Handler
import android.os.Looper
import okhttp3.*
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class RobotSocketClient(
    private val webRtc: WebRtcController,
    private val onStatus: (String) -> Unit,
    private val onViewers: (List<ViewerInfo>, Int) -> Unit = { _, _ -> },
    private val onServoCommand: (ServoCommand) -> Unit = { },
    private val onConnectionChanged: (Boolean) -> Unit = { },
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
    private val queueLock = Any()
    private val pendingCritical = LinkedHashMap<String, QueuedSignal>()
    private val pendingIce = ArrayDeque<QueuedSignal>()
    private var pendingCameraStatus: QueuedSignal? = null

    @Volatile private var socket: WebSocket? = null
    private var serverBase = ""
    private var cameraId = ""
    private var cameraName = ""
    @Volatile private var reconnect = false
    private var reconnectDelay = 500L
    private var maxViewers = 4

    fun connect(base: String, id: String, name: String) {
        disconnectInternal(intentional = true, clearSettings = false)
        serverBase = normalizeBase(base)
        cameraId = id.trim()
        cameraName = name.trim().ifBlank { cameraId }
        reconnect = true
        reconnectDelay = 500L
        open()
    }

    fun disconnect() = disconnectInternal(intentional = true, clearSettings = false)

    private fun disconnectInternal(intentional: Boolean, clearSettings: Boolean) {
        reconnect = false
        generation.incrementAndGet()
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
        onConnectionChanged(false)
        if (clearSettings) { serverBase = ""; cameraId = ""; cameraName = "" }
    }

    fun send(json: JSONObject) {
        val text = json.toString()
        val current = socket
        if (current != null) {
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
        if (!reconnect || serverBase.isBlank() || cameraId.isBlank()) return
        val myGeneration = generation.incrementAndGet()
        val wsBase = when {
            serverBase.startsWith("https://") -> "wss://${serverBase.removePrefix("https://")}" 
            serverBase.startsWith("http://") -> "ws://${serverBase.removePrefix("http://")}" 
            serverBase.startsWith("wss://") || serverBase.startsWith("ws://") -> serverBase
            else -> "ws://$serverBase"
        }
        val url = "$wsBase/ws/camera?cameraId=${enc(cameraId)}&name=${enc(cameraName)}"
        onStatus("server:connecting")

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!isCurrent(webSocket, myGeneration)) return
                reconnectDelay = 500L
                onStatus("server:connected")
                flushPending(webSocket)
                publishCameraStatus()
                onConnectionChanged(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!isCurrent(webSocket, myGeneration)) return
                val msg = try { JSONObject(text) } catch (_: Exception) { return }
                when (msg.optString("type")) {
                    "camera_ready", "robot_ready" -> {
                        maxViewers = msg.optInt("maxViewers", maxViewers).coerceIn(1, 8)
                        publishCameraStatus()
                    }
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

        val newSocket = try { client.newWebSocket(Request.Builder().url(url).build(), listener) } catch (e: Throwable) {
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
        onStatus("server:$detail; reconnecting")
        onConnectionChanged(false)
        scheduleReconnect(myGeneration)
    }

    private fun scheduleReconnect(myGeneration: Long) {
        if (!reconnect || generation.get() != myGeneration) return
        val delay = reconnectDelay
        reconnectDelay = (reconnectDelay * 2).coerceAtMost(8000L)
        main.postDelayed({ if (reconnect && socket == null && generation.get() == myGeneration) open() }, delay)
    }

    private fun normalizeBase(base: String): String = base.trim().removeSuffix("/")
    private fun enc(v: String) = URLEncoder.encode(v, StandardCharsets.UTF_8.toString())
}
