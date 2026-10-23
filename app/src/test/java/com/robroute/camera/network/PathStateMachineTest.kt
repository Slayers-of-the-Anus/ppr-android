package com.robroute.camera.network

import org.junit.Assert.assertEquals
import org.junit.Test

class PathStateMachineTest {
    private val lan = NetworkPath("http://192.168.1.2:8081", "wifi", true)
    private val mobile = NetworkPath("https://example.org", "cellular", false)
    private val remoteWifi = NetworkPath("https://example.org", "wifi", false)

    @Test fun prefersVerifiedLanAndDropsLostRouteImmediately() {
        val machine = PathStateMachine()
        assertEquals(lan, machine.choose(lan, mobile, 100))
        assertEquals(mobile, machine.choose(null, mobile, 101))
        assertEquals(null, machine.choose(null, null, 102))
    }

    @Test fun keepsRemoteWifiDistinctAndDebouncesLanPromotion() {
        val machine = PathStateMachine()
        assertEquals(remoteWifi, machine.choose(null, remoteWifi, 100))
        assertEquals(remoteWifi, machine.choose(lan, remoteWifi, 101))
        assertEquals(lan, machine.choose(lan, remoteWifi, 2_101))
        machine.reset()
        assertEquals(lan, machine.choose(lan, remoteWifi, 2_102))
    }
}
