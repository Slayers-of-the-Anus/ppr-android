package com.robroute.camera.network

data class NetworkPath(val base: String, val transport: String, val lan: Boolean)

/** Keep LAN preference stable while a candidate is probed or briefly disappears. */
class PathStateMachine(private val holdMs: Long = 2_000L) {
    private var lastSwitch = Long.MIN_VALUE
    var current: NetworkPath? = null
        private set

    fun choose(lan: NetworkPath?, public: NetworkPath?, now: Long): NetworkPath? {
        val desired = lan ?: public
        if (current == desired) return current
        // Delay a promotion to LAN briefly; never keep a route whose Network vanished.
        if (current?.lan == false && lan != null && public != null && now - lastSwitch < holdMs) return current
        current = desired
        lastSwitch = now
        return current
    }

    fun reset() { current = null; lastSwitch = Long.MIN_VALUE }
}
