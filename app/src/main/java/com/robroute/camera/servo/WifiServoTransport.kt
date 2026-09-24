package com.robroute.camera.servo

import android.util.Log
import java.io.BufferedOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue

class WifiServoTransport(
    private val listener: ServoTransport.Listener,
) : ServoTransport {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ServoWiFi").apply { isDaemon = true }
    }
    private val commands = LinkedBlockingQueue<String>()
    @Volatile private var started = false
    @Volatile private var socket: Socket? = null
    @Volatile private var discoverySocket: DatagramSocket? = null

    override fun start() {
        if (started) return
        started = true
        executor.execute(::connectionLoop)
    }

    override fun stop() {
        if (!started) return
        started = false
        closeSockets()
        executor.shutdownNow()
    }

    override fun moveTo(angle: Int) = enqueue("S$angle")
    override fun requestStatus() = enqueue("STATUS")

    override fun configureWifi(ssid: String, password: String) {
        listener.onTransportError(
            ServoErrorCode.WIFI_WRITE_FAILED,
            "Wi-Fi credentials can only be provisioned over USB",
        )
    }

    override fun configureMode(mode: String, ssid: String, password: String) {
        listener.onTransportError(ServoErrorCode.WIFI_WRITE_FAILED, "Use the ESP32 setup connection or USB to change modes")
    }

    private fun enqueue(command: String) {
        if (!started || socket?.isConnected != true) {
            listener.onTransportError(ServoErrorCode.WIFI_DISCONNECTED, "ESP32 Wi-Fi link is not connected")
            return
        }
        commands.offer(command)
    }

    private fun connectionLoop() {
        listener.onTransportState(ServoTransportState.Disconnected("searching on local Wi-Fi"))
        while (started) {
            val endpoint = discoverEsp32() ?: continue
            if (!started) break
            try {
                runSession(endpoint)
            } catch (error: Throwable) {
                if (started) {
                    Log.w(TAG, "Wi-Fi session ended", error)
                    listener.onTransportState(ServoTransportState.Disconnected("ESP32 Wi-Fi link disconnected"))
                    listener.onTransportError(
                        ServoErrorCode.WIFI_DISCONNECTED,
                        error.message ?: "ESP32 Wi-Fi link disconnected",
                    )
                }
            } finally {
                socket?.let { try { it.close() } catch (_: Throwable) { } }
                socket = null
                commands.clear()
            }
        }
    }

    private fun discoverEsp32(): InetSocketAddress? {
        val receiver = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(DISCOVERY_PORT))
                soTimeout = DISCOVERY_TIMEOUT_MS
            }
        } catch (error: Throwable) {
            if (started) {
                listener.onTransportError(
                    ServoErrorCode.WIFI_CONNECT_FAILED,
                    error.message ?: "Could not listen for ESP32 discovery",
                )
            }
            return null
        }
        discoverySocket = receiver
        return try {
            val data = ByteArray(256)
            while (started) {
                val packet = DatagramPacket(data, data.size)
                try {
                    receiver.receive(packet)
                    val message = String(packet.data, packet.offset, packet.length, StandardCharsets.US_ASCII).trim()
                    val port = parseDiscoveryPort(message) ?: continue
                    Log.i(TAG, "Discovered ESP32 at ${packet.address.hostAddress}:$port")
                    return InetSocketAddress(packet.address, port)
                } catch (_: SocketTimeoutException) {
                    // Keep listening while the hotspot or ESP32 is starting.
                } catch (error: SocketException) {
                    // stop() closes the receiver to wake this blocking read; that is not a crash.
                    if (started) {
                        Log.w(TAG, "ESP32 discovery socket closed unexpectedly", error)
                        listener.onTransportError(
                            ServoErrorCode.WIFI_CONNECT_FAILED,
                            error.message ?: "ESP32 discovery stopped",
                        )
                    }
                    return null
                }
            }
            null
        } finally {
            try { receiver.close() } catch (_: Throwable) { }
            if (discoverySocket === receiver) discoverySocket = null
        }
    }

    private fun runSession(endpoint: InetSocketAddress) {
        val connected = Socket()
        connected.connect(endpoint, CONNECT_TIMEOUT_MS)
        connected.tcpNoDelay = true
        connected.soTimeout = READ_TIMEOUT_MS
        socket = connected

        val input = connected.getInputStream()
        val output = BufferedOutputStream(connected.getOutputStream())
        // Send the handshake synchronously. Queuing it from the state callback can leave
        // a TCP connection looking healthy even though no command has reached the ESP32.
        writeCommand(output, "STATUS")
        var lastStatusQueryAt = SystemClock.elapsedRealtime()
        var lastReceiveAt = 0L
        val connectedAt = lastStatusQueryAt
        listener.onTransportState(ServoTransportState.Connected("Wi-Fi ${endpoint.address.hostAddress}"))

        val buffer = ByteArray(512)
        while (started && !connected.isClosed) {
            var command = commands.poll()
            while (command != null) {
                try {
                    writeCommand(output, command)
                } catch (error: Throwable) {
                    listener.onTransportError(
                        ServoErrorCode.WIFI_WRITE_FAILED,
                        error.message ?: "ESP32 Wi-Fi write failed",
                    )
                    throw error
                }
                command = commands.poll()
            }
            val now = SystemClock.elapsedRealtime()
            if (now - lastStatusQueryAt >= STATUS_POLL_INTERVAL_MS) {
                writeCommand(output, "STATUS")
                lastStatusQueryAt = now
            }
            if (lastReceiveAt == 0L && now - connectedAt >= FIRST_RESPONSE_TIMEOUT_MS) {
                error("ESP32 did not answer STATUS over Wi-Fi")
            }
            try {
                val count = input.read(buffer)
                if (count < 0) error("ESP32 closed the Wi-Fi link")
                if (count > 0) {
                    lastReceiveAt = SystemClock.elapsedRealtime()
                    Log.d(TAG, "RX bytes: $count")
                    listener.onSerialData(buffer.copyOf(count))
                }
            } catch (_: SocketTimeoutException) {
                // Short timeout lets queued commands be written without a second writer thread.
            }
        }
    }

    private fun writeCommand(output: BufferedOutputStream, command: String) {
        output.write("$command\n".toByteArray(StandardCharsets.US_ASCII))
        output.flush()
        Log.i(TAG, "TX: $command")
    }

    private fun closeSockets() {
        try { discoverySocket?.close() } catch (_: Throwable) { }
        try { socket?.close() } catch (_: Throwable) { }
        discoverySocket = null
        socket = null
    }

    companion object {
        private const val TAG = "ServoWiFi"
        private const val DISCOVERY_PORT = 3334
        private const val DEFAULT_COMMAND_PORT = 3333
        private const val DISCOVERY_TIMEOUT_MS = 1_000
        private const val CONNECT_TIMEOUT_MS = 2_000
        private const val READ_TIMEOUT_MS = 200
        private const val FIRST_RESPONSE_TIMEOUT_MS = 4_000L
        private const val STATUS_POLL_INTERVAL_MS = 5_000L
        private val BEACON = Regex("^PHONEPILOT_ESP32(?:\\s+port=(\\d{1,5}))?(?:\\s+.*)?$")

        internal fun parseDiscoveryPort(message: String): Int? {
            val match = BEACON.matchEntire(message) ?: return null
            val port = match.groupValues[1].takeIf(String::isNotBlank)?.toIntOrNull() ?: DEFAULT_COMMAND_PORT
            return port.takeIf { it in 1..65535 }
        }
    }
}
