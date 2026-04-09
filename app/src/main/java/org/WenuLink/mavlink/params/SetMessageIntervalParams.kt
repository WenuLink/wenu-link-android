package org.WenuLink.mavlink.params

import com.MAVLink.common.msg_command_long

// TODO: Rename file to "TelemetryParams" as soon as more data classes are added

/**
 * Parameter bindings for
 * [MAV_CMD_SET_MESSAGE_INTERVAL](https://mavlink.io/en/messages/common.html#MAV_CMD_SET_MESSAGE_INTERVAL).
 *
 * @param messageId     The MAVLink message ID
 * @param intervalUs    The interval between two messages. -1: disable. 0: request default rate
 *                      (which may be zero).
 */
data class SetMessageIntervalParams(val messageId: Int, val intervalUs: Long) {
    companion object {
        fun from(msg: msg_command_long) = SetMessageIntervalParams(
            messageId = msg.param1.toInt(),
            intervalUs = msg.param2.toLong()
        )
    }
}
