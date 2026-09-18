package com.robroute.camera

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.*

/**
 * Owns exactly one Android -> browser WebRTC upstream.
 * Additional viewers are relayed by the source desktop browser, so weak phones do not create
 * multiple hardware encoders. All native WebRTC entry points are guarded so a negotiation error
 * cannot crash the whole APK or leave a zombie PeerConnection behind.
 */
class WebRtcController(
    private val context: Context,
    private val sendSignal: (JSONObject) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onCaptureStateChanged: () -> Unit = {},
) {
    data class CaptureState(val available: Boolean, val width: Int, val height: Int, val fps: Int)

    private data class SessionContext(
        val id: String,
        val pc: PeerConnection,
        var remoteDescriptionReady: Boolean = false,
        val pendingRemoteIce: MutableList<IceCandidate> = mutableListOf(),
        var connectionState: String = "new",
    )

    private val eglBase = EglBase.create()
    private val factory: PeerConnectionFactory
    private val videoSource: VideoSource
    private val videoTrack: VideoTrack
    private val capturer: CameraVideoCapturer
    private val surfaceTextureHelper: SurfaceTextureHelper
    private val sessionLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var session: SessionContext? = null
    private var currentPreview: SurfaceViewRenderer? = null

    @Volatile private var captureAvailable = false
    @Volatile private var captureWidth = 1280
    @Volatile private var captureHeight = 720
    @Volatile private var captureFps = 30
    @Volatile private var captureStarted = false
    @Volatile private var released = false
    @Volatile private var switchingCamera = false
    private var cameraRestartAttempts = 0

    private val cameraEventsHandler = object : CameraVideoCapturer.CameraEventsHandler {
        override fun onCameraError(errorDescription: String) {
            setCaptureAvailable(false, "camera:error $errorDescription")
            scheduleCameraRestart()
        }
        override fun onCameraDisconnected() {
            setCaptureAvailable(false, "camera:disconnected")
            scheduleCameraRestart()
        }
        override fun onCameraFreezed(errorDescription: String) {
            setCaptureAvailable(false, "camera:frozen $errorDescription")
            scheduleCameraRestart()
        }
        override fun onCameraOpening(cameraName: String) {
            setCaptureAvailable(false, "camera:opening")
        }
        override fun onFirstFrameAvailable() {
            cameraRestartAttempts = 0
            switchingCamera = false
            setCaptureAvailable(true, "camera:${captureWidth}x${captureHeight}@${captureFps}")
        }
        override fun onCameraClosed() {
            setCaptureAvailable(false, if (switchingCamera) "camera:switching" else "camera:closed")
        }
    }

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
        capturer = createCameraCapturer()
        videoSource = factory.createVideoSource(false)
        surfaceTextureHelper = SurfaceTextureHelper.create("RoboRouteCapture", eglBase.eglBaseContext)
        capturer.initialize(surfaceTextureHelper, context.applicationContext, videoSource.capturerObserver)
        videoTrack = factory.createVideoTrack("robroute-camera", videoSource)
    }

    @Synchronized
    fun attachPreview(preview: SurfaceViewRenderer) {
        if (currentPreview === preview) return
        detachPreview()
        try {
            preview.init(eglBase.eglBaseContext, null)
            preview.setMirror(false)
            preview.setEnableHardwareScaler(true)
            videoTrack.addSink(preview)
            currentPreview = preview
        } catch (e: Exception) {
            onStatus("camera:preview error ${e.message ?: "unknown"}")
        }
    }

    @Synchronized
    fun detachPreview(preview: SurfaceViewRenderer? = currentPreview) {
        val target = preview ?: return
        if (currentPreview !== target) return
        try { videoTrack.removeSink(target) } catch (_: Exception) { }
        try { target.release() } catch (_: Exception) { }
        currentPreview = null
    }

    fun startCamera(width: Int = 1280, height: Int = 720, fps: Int = 30) {
        if (captureStarted) return
        captureWidth = width.coerceIn(320, 1920)
        captureHeight = height.coerceIn(240, 1080)
        captureFps = fps.coerceIn(15, 30)
        captureAvailable = false
        captureStarted = true
        onStatus("camera:opening")
        onCaptureStateChanged()
        try {
            capturer.startCapture(captureWidth, captureHeight, captureFps)
        } catch (e: Exception) {
            captureStarted = false
            setCaptureAvailable(false, "camera:error ${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun captureState(): CaptureState = CaptureState(captureAvailable, captureWidth, captureHeight, captureFps)

    fun switchCamera() {
        switchingCamera = true
        setCaptureAvailable(false, "camera:switching")
        try {
            capturer.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
                override fun onCameraSwitchDone(isFrontCamera: Boolean) = Unit
                override fun onCameraSwitchError(errorDescription: String) {
                    switchingCamera = false
                    setCaptureAvailable(false, "camera:error $errorDescription")
                    scheduleCameraRestart()
                }
            })
        } catch (e: Exception) {
            switchingCamera = false
            setCaptureAvailable(false, "camera:error ${e.message ?: e.javaClass.simpleName}")
            scheduleCameraRestart()
        }
    }

    /** Server v0.7 sends at most one source session to Android. */
    fun startSession(newSessionId: String, iceServersJson: JSONArray?, width: Int, height: Int, fps: Int) {
        val sid = newSessionId.trim()
        if (sid.isBlank()) return
        if (!captureAvailable) {
            sendWebRtcErrorDirect(sid, "CAMERA_NOT_READY", "Camera has not produced a frame yet")
            return
        }
        synchronized(sessionLock) {
            if (session?.id == sid) return
        }
        // Never mutate the shared camera format during a WebRTC negotiation. It can invalidate encoders.
        closeAllSessions(false)

        val config = PeerConnection.RTCConfiguration(parseIceServers(iceServersJson)).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        }

        var pcRef: PeerConnection? = null
        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(newState: PeerConnection.SignalingState?) = Unit
            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) = Unit
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) = Unit
            override fun onIceCandidate(candidate: IceCandidate?) {
                val pc = pcRef ?: return
                candidate ?: return
                if (!isCurrentPeer(sid, pc)) return
                sendSignal(JSONObject().apply {
                    put("type", "webrtc_ice")
                    put("sessionId", sid)
                    put("candidate", JSONObject().apply {
                        put("candidate", candidate.sdp)
                        put("sdpMid", candidate.sdpMid)
                        put("sdpMLineIndex", candidate.sdpMLineIndex)
                    })
                })
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
            override fun onAddStream(stream: MediaStream?) = Unit
            override fun onRemoveStream(stream: MediaStream?) = Unit
            override fun onDataChannel(dataChannel: DataChannel?) = Unit
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) = Unit
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                val pc = pcRef ?: return
                if (!isCurrentPeer(sid, pc)) return
                val state = newState?.name?.lowercase() ?: "unknown"
                updateConnectionState(sid, state)
                sendSignal(JSONObject().apply {
                    put("type", "webrtc_state")
                    put("sessionId", sid)
                    put("state", state)
                })
                if (newState == PeerConnection.PeerConnectionState.FAILED) {
                    failSession(sid, "PEER_FAILED", "PeerConnection entered FAILED state")
                }
            }
        }

        val pc = try {
            factory.createPeerConnection(config, observer)
        } catch (e: Throwable) {
            sendWebRtcErrorDirect(sid, "PEER_CREATE_EXCEPTION", describeThrowable(e))
            null
        } ?: run {
            sendWebRtcErrorDirect(sid, "PEER_CREATE_FAILED", "PeerConnectionFactory returned null")
            return
        }
        pcRef = pc
        synchronized(sessionLock) { session = SessionContext(sid, pc) }
        updateAggregateWebRtcStatus()

        try {
            val sender = pc.addTrack(videoTrack, listOf("robroute"))
            if (sender == null) throw IllegalStateException("PeerConnection.addTrack returned null")
        } catch (e: Throwable) {
            failSession(sid, "ADD_TRACK_FAILED", describeThrowable(e))
            return
        }

        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
        try {
            pc.createOffer(object : SimpleSdpObserver() {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    if (desc == null || !isCurrentPeer(sid, pc)) return
                    try {
                        pc.setLocalDescription(object : SimpleSdpObserver() {
                            override fun onSetSuccess() {
                                if (!isCurrentPeer(sid, pc)) return
                                sendSignal(JSONObject().apply {
                                    put("type", "webrtc_offer")
                                    put("sessionId", sid)
                                    put("sdp", desc.description)
                                })
                            }
                            override fun onSetFailure(error: String?) {
                                failSession(sid, "SET_LOCAL_FAILED", error ?: "unknown")
                            }
                        }, desc)
                    } catch (e: Throwable) {
                        failSession(sid, "SET_LOCAL_EXCEPTION", describeThrowable(e))
                    }
                }
                override fun onCreateFailure(error: String?) {
                    failSession(sid, "OFFER_FAILED", error ?: "unknown")
                }
            }, constraints)
        } catch (e: Throwable) {
            failSession(sid, "OFFER_EXCEPTION", describeThrowable(e))
        }
    }

    fun setAnswer(sid: String, sdp: String) {
        val ctx = synchronized(sessionLock) { session?.takeIf { it.id == sid } } ?: return
        if (sdp.isBlank()) return
        val pc = ctx.pc
        try {
            pc.setRemoteDescription(object : SimpleSdpObserver() {
                override fun onSetSuccess() {
                    val queued = mutableListOf<IceCandidate>()
                    synchronized(sessionLock) {
                        val current = session ?: return
                        if (current.id != sid || current.pc !== pc) return
                        current.remoteDescriptionReady = true
                        queued.addAll(current.pendingRemoteIce)
                        current.pendingRemoteIce.clear()
                    }
                    for (candidate in queued) {
                        if (!isCurrentPeer(sid, pc)) return
                        try { pc.addIceCandidate(candidate) } catch (_: Throwable) { }
                    }
                }
                override fun onSetFailure(error: String?) {
                    failSession(sid, "SET_REMOTE_FAILED", error ?: "unknown")
                }
            }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
        } catch (e: Throwable) {
            failSession(sid, "SET_REMOTE_EXCEPTION", describeThrowable(e))
        }
    }

    fun addIceCandidate(sid: String, candidateJson: JSONObject) {
        val text = candidateJson.optString("candidate")
        if (text.isBlank()) return
        val candidate = IceCandidate(candidateJson.optString("sdpMid", "0"), candidateJson.optInt("sdpMLineIndex", 0), text)
        val pc = synchronized(sessionLock) {
            val current = session ?: return
            if (current.id != sid) return
            if (!current.remoteDescriptionReady) {
                if (current.pendingRemoteIce.size >= 128) current.pendingRemoteIce.removeAt(0)
                current.pendingRemoteIce.add(candidate)
                return
            }
            current.pc
        }
        try { pc.addIceCandidate(candidate) } catch (_: Throwable) { }
    }

    fun closeSessionIfMatches(sid: String, notify: Boolean = false) {
        if (sid.isBlank()) return
        val matches = synchronized(sessionLock) { session?.id == sid }
        if (matches) closeSession(sid, notify)
    }

    fun closeSession(sid: String, notify: Boolean = true) {
        val ctx = synchronized(sessionLock) {
            val current = session ?: return
            if (current.id != sid) return
            session = null
            current
        }
        if (notify) sendSignal(JSONObject().apply {
            put("type", "webrtc_stop"); put("sessionId", sid); put("reason", "android-session-closed")
        })
        safeClose(ctx.pc)
        updateAggregateWebRtcStatus()
    }

    fun closeAllSessions(notify: Boolean = false) {
        val sid = synchronized(sessionLock) { session?.id }
        if (sid != null) closeSession(sid, notify) else updateAggregateWebRtcStatus()
    }

    fun release() {
        released = true
        mainHandler.removeCallbacksAndMessages(null)
        closeAllSessions(false)
        captureAvailable = false
        if (captureStarted) {
            try { capturer.stopCapture() } catch (_: Throwable) { }
            captureStarted = false
        }
        try { capturer.dispose() } catch (_: Throwable) { }
        detachPreview()
        try { videoTrack.dispose() } catch (_: Throwable) { }
        try { videoSource.dispose() } catch (_: Throwable) { }
        try { surfaceTextureHelper.dispose() } catch (_: Throwable) { }
        try { factory.dispose() } catch (_: Throwable) { }
        try { eglBase.release() } catch (_: Throwable) { }
    }


    private fun scheduleCameraRestart() {
        if (released || switchingCamera) return
        val attempt = (++cameraRestartAttempts).coerceAtMost(6)
        val delay = (750L * attempt).coerceAtMost(5000L)
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({
            if (released || switchingCamera || captureAvailable) return@postDelayed
            try {
                if (captureStarted) capturer.stopCapture()
            } catch (_: Throwable) { }
            captureStarted = false
            startCamera(captureWidth, captureHeight, captureFps)
        }, delay)
    }

    private fun failSession(sid: String, code: String, detail: String) {
        val exists = synchronized(sessionLock) { session?.id == sid }
        if (!exists) return
        sendWebRtcErrorDirect(sid, code, detail)
        closeSession(sid, false)
    }

    private fun setCaptureAvailable(available: Boolean, status: String) {
        captureAvailable = available
        onStatus(status)
        onCaptureStateChanged()
    }

    private fun updateConnectionState(sid: String, state: String) {
        synchronized(sessionLock) { session?.takeIf { it.id == sid }?.connectionState = state }
        updateAggregateWebRtcStatus()
    }

    private fun updateAggregateWebRtcStatus() {
        val state = synchronized(sessionLock) { session?.connectionState }
        onStatus("webrtc:${state ?: "idle"}")
    }

    private fun isCurrentPeer(sid: String, pc: PeerConnection): Boolean = synchronized(sessionLock) {
        session?.let { it.id == sid && it.pc === pc } == true
    }

    private fun safeClose(pc: PeerConnection) {
        try { pc.close() } catch (_: Throwable) { }
        try { pc.dispose() } catch (_: Throwable) { }
    }

    private fun createCameraCapturer(): CameraVideoCapturer {
        val enumerator: CameraEnumerator = if (Camera2Enumerator.isSupported(context)) Camera2Enumerator(context) else Camera1Enumerator(true)
        val names = enumerator.deviceNames
        val selected = names.firstOrNull { enumerator.isBackFacing(it) } ?: names.firstOrNull() ?: error("No camera found")
        return enumerator.createCapturer(selected, cameraEventsHandler) ?: error("Unable to create camera capturer")
    }

    private fun parseIceServers(array: JSONArray?): List<PeerConnection.IceServer> {
        if (array == null) return emptyList()
        val out = mutableListOf<PeerConnection.IceServer>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val urls = mutableListOf<String>()
            when (val value = item.opt("urls")) {
                is JSONArray -> for (j in 0 until value.length()) value.optString(j).takeIf { it.isNotBlank() }?.let(urls::add)
                is String -> if (value.isNotBlank()) urls.add(value)
            }
            if (urls.isEmpty()) continue
            val builder = PeerConnection.IceServer.builder(urls)
            item.optString("username", "").takeIf { it.isNotEmpty() }?.let { builder.setUsername(it) }
            item.optString("credential", "").takeIf { it.isNotEmpty() }?.let { builder.setPassword(it) }
            out += builder.createIceServer()
        }
        return out
    }

    private fun sendWebRtcErrorDirect(sid: String, code: String, detail: String) {
        sendSignal(JSONObject().apply {
            put("type", "webrtc_error")
            put("sessionId", sid)
            put("code", code)
            put("detail", detail.take(500))
        })
        onStatus("webrtc:error $code · ${detail.take(120)}")
    }

    private fun describeThrowable(t: Throwable): String = buildString {
        append(t.javaClass.simpleName)
        if (!t.message.isNullOrBlank()) append(": ").append(t.message)
        t.cause?.let { cause ->
            if (cause !== t) {
                append(" | cause=").append(cause.javaClass.simpleName)
                if (!cause.message.isNullOrBlank()) append(": ").append(cause.message)
            }
        }
    }.take(500)
}
