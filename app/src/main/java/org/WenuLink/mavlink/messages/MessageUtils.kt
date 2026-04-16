package org.WenuLink.mavlink.messages

import com.MAVLink.Messages.MAVLinkMessage
import com.MAVLink.common.msg_command_ack
import com.MAVLink.enums.MAV_CMD
import com.MAVLink.enums.MAV_RESULT
import kotlin.math.roundToInt
import org.WenuLink.adapters.aircraft.Coordinates3D

object MessageUtils {
    fun toBoolean(param: Float): Boolean? = when (param) {
        1f -> true
        0f -> false
        else -> null
    }

    fun getMicroTime(): Long = System.currentTimeMillis() * 1000

    // float deg to deg E7
    fun coordinateDJI2MAVLink(value: Double): Int = (10_000_000 * value).roundToInt()

    // deg E7 to float
    fun coordinateMAVLink2DJI(value: Int): Double = value / 10_000_000.0

    // meters to millimeters
    fun altitudeDJI2MAVLink(value: Float): Int = (value * 1000).roundToInt()

    fun packVersion(major: Int, minor: Int, patch: Int, type: Int): Long = (
        (major shl 24) or
            (minor shl 16) or
            (patch shl 8) or
            (type and 0xFF)
        ).toLong()

    fun msgCommandAck(
        messageID: Int,
        result: Int = MAV_RESULT.MAV_RESULT_UNSUPPORTED,
        progress: Int = -1
    ): MAVLinkMessage = msg_command_ack().apply {
        command = messageID
        if (progress > -1) {
            this.result = MAV_RESULT.MAV_RESULT_IN_PROGRESS.toShort()
            this.progress = progress.toShort()
        } else {
            this.result = result.toShort()
        }
    }

    fun msgRequestAck(
        result: Int = MAV_RESULT.MAV_RESULT_DENIED,
        progress: Int = -1
    ): MAVLinkMessage = msgCommandAck(MAV_CMD.MAV_CMD_REQUEST_MESSAGE, result, progress)

    fun toShortArray(input: String, bytesSize: Int = 32): ShortArray =
        ShortArray(bytesSize).also { shortArray ->
            input.take(bytesSize).forEachIndexed { index, char ->
                shortArray[index] = char.code.toShort()
            }
        }

    fun xyzMAVLink2Coordinates(x: Int, y: Int, z: Float): Coordinates3D = Coordinates3D(
        coordinateMAVLink2DJI(x),
        coordinateMAVLink2DJI(y),
        z
    )
}
