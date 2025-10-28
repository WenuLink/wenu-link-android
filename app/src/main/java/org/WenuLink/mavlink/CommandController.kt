package org.WenuLink.mavlink

import android.util.Log
import com.MAVLink.Messages.MAVLinkMessage
import com.MAVLink.common.msg_autopilot_version
import com.MAVLink.common.msg_autopilot_version.MAVLINK_MSG_ID_AUTOPILOT_VERSION
import com.MAVLink.common.msg_command_ack
import com.MAVLink.common.msg_command_long
import com.MAVLink.enums.MAV_CMD
import com.MAVLink.enums.MAV_PROTOCOL_CAPABILITY
import com.MAVLink.enums.MAV_RESULT
import com.MAVLink.minimal.msg_protocol_version
import com.MAVLink.minimal.msg_protocol_version.MAVLINK_MSG_ID_PROTOCOL_VERSION

class CommandController (
    private var client: MAVLinkClient
) : MAVLinkController {
    private val TAG: String = CommandController::class.java.simpleName

    // https://ardupilot.org/copter/docs/ArduCopter_MAVLink_Messages.html#incoming-commands
    // https://mavlink.io/en/services/command.html
    override fun processMessage(msg: MAVLinkMessage) {
        val longMsg = msg as msg_command_long
        if (longMsg.target_system != 0.toShort() && longMsg.target_system != client.systemID.toShort()) {
            return
        }
        Log.i(TAG, "\t - has a command ID: ${longMsg.msgid}")
        when (longMsg.command) {
            MAV_CMD.MAV_CMD_REQUEST_MESSAGE -> requestMessageCallback(
                longMsg.param1.toInt(),
                longMsg.param2.toInt()
            )
            // TODO: Unhandled command ID: 521.
            // MAV_CMD.MAV_CMD_REQUEST_CAMERA_INFORMATION -> {}
            // TODO: Unhandled command ID: 400.
            // MAV_CMD.MAV_CMD_COMPONENT_ARM_DISARM -> {}
            else -> {
                Log.e(TAG, "Unhandled command ID: ${longMsg.command}.")
                sendCommandAck(longMsg.command)
            }
        }
    }

    // https://ardupilot.org/copter/docs/ArduCopter_MAVLink_Messages.html#requestable-messages
    fun requestMessageCallback(requestID: Int, paramID: Int) {
        Log.i(TAG, "\t - has a requestID: ${requestID}")
        when (requestID) {
            MAVLINK_MSG_ID_AUTOPILOT_VERSION -> sendAutopilot()
            MAVLINK_MSG_ID_PROTOCOL_VERSION -> sendProtocolVersion()
            // TODO: Unhandled request ID: 280
            // https://mavlink.io/en/messages/common.html#GIMBAL_MANAGER_INFORMATION
            // MAVLINK_MSG_ID_GIMBAL_MANAGER_INFORMATION -> {}
            // TODO: Unhandled request ID: 395 (replaced by 397)
            // TODO: Unhandled request ID: 397
            // https://mavlink.io/en/messages/common.html#COMPONENT_METADATA
            // MAVLINK_MSG_ID_COMPONENT_METADATA -> {}// MAVLink WIP
            // TODO: Unhandled request ID: 435
            // https://mavlink.io/en/messages/common.html#AVAILABLE_MODES
            // MAVLINK_MSG_ID_AVAILABLE_MODES -> {}
            // TODO: Unhandled request ID: 521
            // MAV_CMD.MAV_CMD_REQUEST_CAMERA_INFORMATION -> {}
            // MAVLINK_MSG_ID_CAMERA_INFORMATION // 259
            else -> {
                Log.e(TAG, "Unhandled request ID: ${requestID}.")
                sendCommandAck(MAV_CMD.MAV_CMD_REQUEST_MESSAGE)
            }
        }
    }

    fun sendCommandAck(messageID: Int, result: Int = MAV_RESULT.MAV_RESULT_DENIED, progress: Int = -1) {
        val msg = msg_command_ack()
        msg.command = messageID
        if (progress > -1) {
            msg.result = MAV_RESULT.MAV_RESULT_IN_PROGRESS.toShort()
            msg.progress = progress.toShort()
        } else {
            msg.result = result.toShort()
        }
        client.sendMessage(msg)
    }

    fun sendAutopilot() {
        val msg = msg_autopilot_version()
        msg.capabilities = MAV_PROTOCOL_CAPABILITY.MAV_PROTOCOL_CAPABILITY_COMMAND_INT.toLong()
        msg.capabilities.or(MAV_PROTOCOL_CAPABILITY.MAV_PROTOCOL_CAPABILITY_MISSION_INT.toLong())
        msg.os_sw_version = 0x040107
        msg.middleware_sw_version = 0x040107
        msg.flight_sw_version = 0x040107
        client.sendMessage(msg)
    }

    fun sendProtocolVersion() {
        val msg = msg_protocol_version()
        msg.version = 200
        client.sendMessage(msg)
    }
}