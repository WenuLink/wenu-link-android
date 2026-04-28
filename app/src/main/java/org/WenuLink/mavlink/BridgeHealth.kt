package org.WenuLink.mavlink

data class BridgeHealth(
    val rxPerSec: Int,
    val txPerSec: Int,
    val lastGcsHeartbeatAt: Long?,
    val parseErrors: Long
) {
    companion object {
        val idle = BridgeHealth(
            rxPerSec = 0,
            txPerSec = 0,
            lastGcsHeartbeatAt = null,
            parseErrors = 0L
        )
    }
}
