package org.WenuLink.adapters

import com.MAVLink.Messages.MAVLinkMessage
import com.MAVLink.common.msg_command_ack
import com.MAVLink.enums.MAV_CMD
import com.MAVLink.enums.MAV_RESULT
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

object AsyncUtils {
    suspend fun waitReadiness(
        delayTime: Long = 100,
        timeout: Long = 2000,
        invertCondition: Boolean = false,
        isReady: () -> Boolean,
        onResult: (Boolean) -> Unit
    ) {
        fun preparedReadyCondition() = if (invertCondition) isReady() else !isReady()
        waitTimeout(delayTime, timeout, ::preparedReadyCondition)
        onResult(preparedReadyCondition())
    }

    suspend fun waitTimeout(
        intervalTime: Long = 100,
        timeout: Long = 2000,
        isReady: () -> Boolean
    ): Boolean {
        val startTime = System.currentTimeMillis()

        if (timeout == -1L) waitReady(intervalTime, isReady)
        else while (!isReady() && System.currentTimeMillis() - startTime < timeout) {
            delay(intervalTime) // Wait for the next check
        }
        return isReady()
    }

    suspend fun waitReady(
        intervalTime: Long = 10,
        isReady: () -> Boolean
    ) {
        while (!isReady()) {
            delay(intervalTime) // Wait for the next check
        }
    }
}


object MessageUtils {
    fun getMicroTime(): Long = System.currentTimeMillis() * 1_000

    // float deg to deg E7
    fun coordinateDJI2MAVLink(value: Double): Int = (10_000_000 * value).roundToInt()

    // deg E7 to float
    fun coordinateMAVLink2DJI(value: Int): Double  = value.toDouble() / 10_000_000.0

    // meters to millimeters
    fun altitudeDJI2MAVLink(value: Float): Int = (value * 1_000).roundToInt()

    fun packVersion(
        major: Int,
        minor: Int,
        patch: Int,
        type: Int
    ): Long {
        return ((major shl 24) or
                (minor shl 16) or
                (patch shl 8) or
                (type and 0xFF)).toLong()
    }

    fun msgCommandAck(messageID: Int, result: Int = MAV_RESULT.MAV_RESULT_UNSUPPORTED, progress: Int = -1): MAVLinkMessage {
        val msg = msg_command_ack()
        msg.command = messageID
        if (progress > -1) {
            msg.result = MAV_RESULT.MAV_RESULT_IN_PROGRESS.toShort()
            msg.progress = progress.toShort()
        } else {
            msg.result = result.toShort()
        }
        return msg
    }

    fun msgRequestAck(result: Int = MAV_RESULT.MAV_RESULT_DENIED, progress: Int = -1): MAVLinkMessage {
        return msgCommandAck(MAV_CMD.MAV_CMD_REQUEST_MESSAGE, result, progress)
    }

    fun toShortArray(input: String, bytesSize: Int = 32): ShortArray {
        return ShortArray(bytesSize).also { shortArray ->
            input.take(bytesSize).forEachIndexed { index, char ->
                shortArray[index] = char.code.toShort()
            }
        }
    }


}

object OrientationUtils {

    fun eulerDegToQuaternion(
        rollDeg: Double,
        pitchDeg: Double,
        yawDeg: Double
    ): Quaternion {

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
