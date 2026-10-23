package com.robroute.camera.servo

import java.nio.charset.StandardCharsets

sealed interface ServoResponse {
    data class DriveStatus(val ready: Boolean, val direction: String) : ServoResponse
    data class Status(val ready: Boolean, val angle: Int, val min: Int, val max: Int) : ServoResponse
    data class AngleAccepted(val angle: Int) : ServoResponse
    data class Error(val code: String, val detail: String) : ServoResponse
    data object WifiSaved : ServoResponse
    data class ConfigSaved(val mode: String) : ServoResponse
    data object WifiConnecting : ServoResponse
    data class WifiConnected(val ip: String) : ServoResponse
    data class WifiStatus(val state: String, val ip: String) : ServoResponse
    data class Invalid(val line: String) : ServoResponse
}

class ServoLineParser(private val maxLineLength: Int = 512) {
    private val buffer = StringBuilder()

    @Synchronized
    fun feed(data: ByteArray): List<ServoResponse> {
        val results = mutableListOf<ServoResponse>()
        for (char in String(data, StandardCharsets.US_ASCII)) {
            when (char) {
                '\r' -> Unit
                '\n' -> {
                    if (buffer.isNotEmpty()) results += parseLine(buffer.toString())
                    buffer.setLength(0)
                }
                else -> {
                    if (buffer.length < maxLineLength) buffer.append(char)
                    else {
                        results += ServoResponse.Invalid(buffer.toString())
                        buffer.setLength(0)
                    }
                }
            }
        }
        return results
    }

    @Synchronized
    fun reset() = buffer.setLength(0)

    companion object {
        private val anglePattern = Regex("^OK\\s+ANGLE\\s+(-?\\d+)$", RegexOption.IGNORE_CASE)

        fun parseLine(raw: String): ServoResponse {
            val line = raw.trim()
            if (line.startsWith("DRIVE_STATE ")) {
                val values = line.substringAfter(' ').split(Regex("\\s+")).mapNotNull { token ->
                    val parts = token.split('=', limit = 2)
                    if (parts.size == 2) parts[0] to parts[1] else null
                }.toMap()
                val ready = values["ready"]?.toIntOrNull()
                val direction = values["direction"]
                return if (ready in 0..1 && direction in setOf("forward", "backward", "stop"))
                    ServoResponse.DriveStatus(ready == 1, direction!!) else ServoResponse.Invalid(line)
            }
            if (line.equals("OK WIFI_SAVED", ignoreCase = true)) return ServoResponse.WifiSaved
            if (line.startsWith("OK CONFIG_SAVED mode=", ignoreCase = true)) {
                return ServoResponse.ConfigSaved(line.substringAfter("mode=").trim())
            }
            if (line.equals("WIFI CONNECTING", ignoreCase = true)) return ServoResponse.WifiConnecting
            if (line.startsWith("WIFI CONNECTED ", ignoreCase = true)) {
                return ServoResponse.WifiConnected(line.substringAfter("ip=", "").trim())
            }
            if (line.startsWith("WIFI STATUS ", ignoreCase = true)) {
                val values = line.substringAfter("WIFI STATUS ").split(Regex("\\s+")).mapNotNull { token ->
                    val parts = token.split('=', limit = 2)
                    if (parts.size == 2) parts[0].lowercase() to parts[1] else null
                }.toMap()
                return ServoResponse.WifiStatus(values["state"].orEmpty(), values["ip"].orEmpty())
            }
            anglePattern.matchEntire(line)?.let {
                return it.groupValues[1].toIntOrNull()?.let(ServoResponse::AngleAccepted)
                    ?: ServoResponse.Invalid(line)
            }
            if (line.startsWith("STATUS ", ignoreCase = true)) {
                val values = line.substringAfter(' ').split(Regex("\\s+")).mapNotNull { token ->
                    val parts = token.split('=', limit = 2)
                    if (parts.size == 2) parts[0].lowercase() to parts[1] else null
                }.toMap()
                val ready = values["ready"]?.toIntOrNull()
                val angle = values["angle"]?.toIntOrNull()
                val min = values["min"]?.toIntOrNull()
                val max = values["max"]?.toIntOrNull()
                if (ready in 0..1 && angle != null && min != null && max != null && min <= max) {
                    return ServoResponse.Status(ready == 1, angle, min, max)
                }
                return ServoResponse.Invalid(line)
            }
            if (line.startsWith("ERR ", ignoreCase = true)) {
                val body = line.substring(4).trim()
                val code = body.substringBefore(' ').ifBlank { "UNKNOWN" }
                return ServoResponse.Error(code, body.substringAfter(' ', ""))
            }
            return ServoResponse.Invalid(line)
        }
    }
}
