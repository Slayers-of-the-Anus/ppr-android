package com.robroute.camera.servo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServoProtocolTest {
    @Test fun parsesDriveCapabilityAndStopAfterWatchdog() {
        val parser = ServoLineParser()
        assertEquals(listOf(
            ServoResponse.Status(true, 90, 0, 180),
            ServoResponse.DriveStatus(true, "forward"),
            ServoResponse.DriveStatus(true, "stop"),
        ), parser.feed("STATUS ready=1 angle=90 min=0 max=180\nDRIVE_STATE ready=1 direction=forward\nDRIVE_STATE ready=1 direction=stop\n".toByteArray()))
        assertTrue(ServoLineParser.parseLine("DRIVE_STATE ready=1 direction=left") is ServoResponse.Invalid)
        assertTrue(ServoLineParser.parseLine("DRIVE_STATE ready=5 direction=stop") is ServoResponse.Invalid)
    }
    @Test fun parsesAngleAck() {
        assertEquals(ServoResponse.AngleAccepted(95), ServoLineParser.parseLine("OK ANGLE 95"))
    }

    @Test fun parsesStatus() {
        assertEquals(
            ServoResponse.Status(true, 90, 0, 180),
            ServoLineParser.parseLine("STATUS ready=1 angle=90 min=0 max=180"),
        )
    }

    @Test fun parsesEsp32Error() {
        assertEquals(
            ServoResponse.Error("SERVO_NOT_READY", ""),
            ServoLineParser.parseLine("ERR SERVO_NOT_READY"),
        )
    }

    @Test fun rejectsInvalidLine() {
        assertTrue(ServoLineParser.parseLine("not a response") is ServoResponse.Invalid)
    }

    @Test fun buffersPartialPackets() {
        val parser = ServoLineParser()
        assertTrue(parser.feed("OK ANG".toByteArray()).isEmpty())
        assertEquals(listOf(ServoResponse.AngleAccepted(95)), parser.feed("LE 95\n".toByteArray()))
    }

    @Test fun parsesMultipleCrLfLinesInOnePacket() {
        val parser = ServoLineParser()
        assertEquals(
            listOf(
                ServoResponse.Status(true, 90, 0, 180),
                ServoResponse.AngleAccepted(95),
                ServoResponse.Error("SERVO_NOT_READY", ""),
            ),
            parser.feed("STATUS ready=1 angle=90 min=0 max=180\r\nOK ANGLE 95\nERR SERVO_NOT_READY\r\n".toByteArray()),
        )
    }

    @Test fun parsesWifiProvisioningResponses() {
        val parser = ServoLineParser()
        assertEquals(
            listOf(
                ServoResponse.WifiSaved,
                ServoResponse.WifiConnecting,
                ServoResponse.WifiConnected("192.168.43.22"),
                ServoResponse.WifiStatus("connected", "192.168.43.22"),
            ),
            parser.feed(
                "OK WIFI_SAVED\nWIFI CONNECTING\nWIFI CONNECTED ip=192.168.43.22\n".plus(
                    "WIFI STATUS state=connected ip=192.168.43.22\n",
                ).toByteArray(),
            ),
        )
    }

    @Test fun parsesWifiDiscoveryBeacon() {
        assertEquals(3333, WifiServoTransport.parseDiscoveryPort("PHONEPILOT_ESP32 port=3333"))
        assertEquals(3333, WifiServoTransport.parseDiscoveryPort("PHONEPILOT_ESP32"))
        assertEquals(null, WifiServoTransport.parseDiscoveryPort("OTHER_DEVICE port=3333"))
        assertEquals(null, WifiServoTransport.parseDiscoveryPort("PHONEPILOT_ESP32 port=70000"))
    }

    @Test fun parsesConfigurationConfirmation() {
        assertEquals(
            ServoResponse.ConfigSaved("SHARED"),
            ServoLineParser.parseLine("OK CONFIG_SAVED mode=SHARED"),
        )
    }
}
