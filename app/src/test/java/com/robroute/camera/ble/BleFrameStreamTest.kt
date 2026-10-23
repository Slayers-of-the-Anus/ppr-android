package com.robroute.camera.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BleFrameStreamTest {
    private fun frame(value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return byteArrayOf(bytes.size.toByte(), (bytes.size shr 8).toByte()) + bytes
    }

    @Test fun fragmentedAndCoalescedFrames() {
        val stream = BleFrameStream()
        val first = frame("{\"v\":1,\"type\":\"ack\"}")
        val second = frame("{\"v\":1,\"type\":\"status\"}")
        assertEquals(emptyList<String>(), stream.feed(first.copyOfRange(0, 3), 100))
        assertEquals(listOf("{\"v\":1,\"type\":\"ack\"}", "{\"v\":1,\"type\":\"status\"}"),
            stream.feed(first.copyOfRange(3, first.size) + second, 101))
    }

    @Test fun invalidLengthAndExpiredPartialFrame() {
        val stream = BleFrameStream()
        assertThrows(IllegalArgumentException::class.java) { stream.feed(byteArrayOf(1, 2), 100) }
        val payload = frame("{\"v\":1}")
        assertEquals(emptyList<String>(), stream.feed(payload.copyOfRange(0, 3), 200))
        assertThrows(IllegalArgumentException::class.java) { stream.feed(payload.copyOfRange(3, payload.size), 6_000) }
        assertEquals(listOf("{\"v\":1}"), stream.feed(payload, 6_001))
    }
}
