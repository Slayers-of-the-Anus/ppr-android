package com.robroute.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraAuthTest {
    @Test fun proofMatchesWireContract() {
        assertEquals("By14gNMoAbq-WgqzoI2ZVpQQvQVpQQDb4QqOFS8kB_E",
            RobotSocketClient.cameraProof(
                "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
                "AAECAwQFBgcICQoLDA0ODw", "phone_01"))
        assertNull(RobotSocketClient.cameraProof("bad", "AAECAwQFBgcICQoLDA0ODw", "phone_01"))
    }
}
