package com.robroute.camera.ble

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class BleContractTest {
    private val id = "123e4567-e89b-12d3-a456-426614174000"

    @Test fun selectModeHasNoCredentials() {
        val json = JsonParser.parseString(BleProtocol.selectModeJson("USB", id)).asJsonObject
        assertEquals(1, json.get("v").asInt)
        assertEquals("select_mode", json.get("type").asString)
        assertEquals(id, json.get("requestId").asString)
        assertEquals("USB", json.get("mode").asString)
        assertFalse(json.has("ssid"))
        assertFalse(json.has("password"))
    }

    @Test fun configEscapesNetworkNameAndEnforcesByteLimits() {
        val json = JsonParser.parseString(BleProtocol.setConfigJson("SHARED", "Lab \"A\"", "password123", id)).asJsonObject
        assertEquals("set_config", json.get("type").asString)
        assertEquals("Lab \"A\"", json.get("ssid").asString)
        assertEquals("password123", json.get("password").asString)
        assertThrows(IllegalArgumentException::class.java) { BleProtocol.setConfigJson("SHARED", "é".repeat(17), "password123", id) }
        assertThrows(IllegalArgumentException::class.java) { BleProtocol.setConfigJson("HOTSPOT", "Lab", "short", id) }
    }
}
