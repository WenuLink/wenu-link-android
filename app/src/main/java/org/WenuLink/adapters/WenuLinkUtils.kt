package org.WenuLink.adapters

import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay
import org.WenuLink.adapters.aircraft.Quaternion

object AsyncUtils {
    suspend fun waitTimeout(
        intervalTime: Long = 100L,
        timeout: Long = 2000L,
        isReady: () -> Boolean
    ): Boolean {
        val startTime = System.currentTimeMillis()

        if (timeout == -1L) {
            waitReady(intervalTime, isReady)
        } else {
            while (!isReady() && System.currentTimeMillis() - startTime < timeout) {
                delay(intervalTime) // Wait for the next check
            }
        }
        return isReady()
    }

    suspend fun waitReady(intervalTime: Long = 10L, isReady: () -> Boolean) {
        while (!isReady()) {
            delay(intervalTime) // Wait for the next check
        }
    }
}

object OrientationUtils {
    fun eulerDegToQuaternion(rollDeg: Double, pitchDeg: Double, yawDeg: Double): Quaternion {
        val roll = Math.toRadians(rollDeg)
        val pitch = Math.toRadians(pitchDeg)
        val yaw = Math.toRadians(yawDeg)

        val cr = cos(roll * 0.5)
        val sr = sin(roll * 0.5)
        val cp = cos(pitch * 0.5)
        val sp = sin(pitch * 0.5)
        val cy = cos(yaw * 0.5)
        val sy = sin(yaw * 0.5)

        val w = cr * cp * cy + sr * sp * sy
        val x = sr * cp * cy - cr * sp * sy
        val y = cr * sp * cy + sr * cp * sy
        val z = cr * cp * sy - sr * sp * cy

        return Quaternion(w, x, y, z).normalized()
    }
}

data class ServiceAddress(val ip: String, val port: Int, val protocol: String) {
    override fun toString(): String = "$protocol://$ip:$port".lowercase()
}
