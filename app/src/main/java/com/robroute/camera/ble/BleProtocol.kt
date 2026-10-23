package com.robroute.camera.ble

import com.google.gson.JsonObject
import org.json.JSONObject
import java.util.UUID

object BleProtocol {
    const val MAX_JSON = 512
    val SERVICE: UUID = UUID.fromString("6c40cb73-3396-4ec6-bcc0-cb2db37ace10")
    val COMMAND: UUID = UUID.fromString("6c40cb73-3396-4ec6-bcc0-cb2db37ace11")
    val STATUS: UUID = UUID.fromString("6c40cb73-3396-4ec6-bcc0-cb2db37ace12")

    fun frame(json: JSONObject): ByteArray {
        require(json.optInt("v", -1) == 1)
        val body = json.toString().toByteArray(Charsets.UTF_8)
        require(body.size in 1..MAX_JSON)
        return byteArrayOf((body.size and 255).toByte(), (body.size ushr 8).toByte()) + body
    }

    fun selectMode(mode: String, requestId: String = UUID.randomUUID().toString()): JSONObject {
        return JSONObject(selectModeJson(mode, requestId))
    }

    fun selectModeJson(mode: String, requestId: String = UUID.randomUUID().toString()): String {
        require(mode in setOf("USB", "SHARED", "HOTSPOT"))
        require(runCatching { UUID.fromString(requestId) }.isSuccess)
        return JsonObject().apply {
            addProperty("v", 1); addProperty("type", "select_mode")
            addProperty("requestId", requestId); addProperty("mode", mode)
        }.toString()
    }

    fun setConfig(mode: String, ssid: String, password: String, requestId: String = UUID.randomUUID().toString()): JSONObject {
        return JSONObject(setConfigJson(mode, ssid, password, requestId))
    }

    fun setConfigJson(mode: String, ssid: String, password: String, requestId: String = UUID.randomUUID().toString()): String {
        require(mode == "SHARED" || mode == "HOTSPOT")
        require(ssid.toByteArray(Charsets.UTF_8).size in 1..32)
        require(password.toByteArray(Charsets.UTF_8).size in 8..63)
        require(runCatching { UUID.fromString(requestId) }.isSuccess)
        return JsonObject().apply {
            addProperty("v", 1); addProperty("type", "set_config")
            addProperty("requestId", requestId); addProperty("mode", mode)
            addProperty("ssid", ssid); addProperty("password", password)
        }.toString()
    }
}

/** A characteristic carries a byte stream, not one JSON document per callback. */
class BleFrameDecoder(private val timeoutMs: Long = 5_000) {
    private val stream = BleFrameStream(timeoutMs)

    fun feed(bytes: ByteArray, now: Long = System.currentTimeMillis()): List<JSONObject> =
        stream.feed(bytes, now).map { text ->
            JSONObject(text).also { if (it.optInt("v", -1) != 1) throw IllegalArgumentException("Unsupported BLE version") }
        }

    fun reset() = stream.reset()
}

class BleFrameStream(private val timeoutMs: Long = 5_000) {
    private val pending = ArrayList<Byte>()
    private var beganAt = 0L

    fun feed(bytes: ByteArray, now: Long = System.currentTimeMillis()): List<String> {
        if (beganAt != 0L && now - beganAt > timeoutMs) reset()
        if (bytes.isNotEmpty() && pending.isEmpty()) beganAt = now
        if (pending.size + bytes.size > (BleProtocol.MAX_JSON + 2) * 4) {
            reset()
            throw IllegalArgumentException("BLE stream overflow")
        }
        for (byte in bytes) pending.add(byte)
        val result = mutableListOf<String>()
        while (pending.size >= 2) {
            val length = (pending[0].toInt() and 255) or ((pending[1].toInt() and 255) shl 8)
            if (length !in 1..BleProtocol.MAX_JSON) { reset(); throw IllegalArgumentException("Invalid BLE frame length") }
            if (pending.size < length + 2) break
            val body = ByteArray(length) { pending[it + 2] }
            repeat(length + 2) { pending.removeAt(0) }
            result.add(String(body, Charsets.UTF_8))
            beganAt = if (pending.isEmpty()) 0 else now
        }
        return result
    }

    fun reset() { pending.clear(); beganAt = 0 }
}
