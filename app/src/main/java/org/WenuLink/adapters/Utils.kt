package org.WenuLink.adapters

import com.MAVLink.Messages.MAVLinkMessage
import com.MAVLink.common.msg_command_ack
import com.MAVLink.enums.MAV_CMD
import com.MAVLink.enums.MAV_RESULT
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

object Utils {
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

        while (!isReady() && System.currentTimeMillis() - startTime < timeout) {
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

    fun getMicroTime(): Long = System.currentTimeMillis() * 1_000

    // float deg to deg E7
    fun coordinateDJI2MAVLink(value: Double): Int = (10_000_000 * value).roundToInt()

    // deg E7 to float
    fun coordinateMAVLink2DJI(value: Int): Double  = value.toDouble() / 10_000_000.0

    // meters to millimeters
    fun altitudeDJI2MAVLink(value: Float): Int = (value * 1_000).toInt()

    fun commandAckMsg(messageID: Int, result: Int = MAV_RESULT.MAV_RESULT_UNSUPPORTED, progress: Int = -1): MAVLinkMessage {
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

    fun requestAckMsg(result: Int = MAV_RESULT.MAV_RESULT_DENIED, progress: Int = -1): MAVLinkMessage {
        return commandAckMsg(MAV_CMD.MAV_CMD_REQUEST_MESSAGE, result, progress)
    }

}
