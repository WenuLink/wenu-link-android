package org.WenuLink.controllers

import com.MAVLink.Messages.MAVLinkMessage
import com.MAVLink.common.msg_autopilot_version
import com.MAVLink.common.msg_command_long
import com.MAVLink.enums.FIRMWARE_VERSION_TYPE
import com.MAVLink.enums.MAV_CMD
import com.MAVLink.enums.MAV_PROTOCOL_CAPABILITY
import com.MAVLink.enums.MAV_RESULT
import io.getstream.log.taggedLogger
import kotlinx.coroutines.CoroutineScope
import org.WenuLink.adapters.AircraftHandler
import org.WenuLink.adapters.MessageUtils
import org.WenuLink.mavlink.MAVLinkClient
import kotlin.getValue

/**
 * MAVLinkController class to deal with the command service and related MAVLink messages.
 *
 * https://mavlink.io/en/services/command.html
 *
 * https://ardupilot.org/copter/docs/ArduCopter_MAVLink_Messages.html#incoming-commands
 */
class CommandController (
    override var client: MAVLinkClient
): IController {
    private val logger by taggedLogger("CommandController")

    override fun processMessage(msg: MAVLinkMessage, aircraft: AircraftHandler): Boolean {
        var processed = true
        when (msg.msgid) {
            msg_autopilot_version.MAVLINK_MSG_ID_AUTOPILOT_VERSION -> sendAutopilotAck()
            else -> processed = false
        }
        return processed
    }

    override fun processCommandLong(commandLongMsg: msg_command_long, aircraft: AircraftHandler, serviceScope: CoroutineScope): Boolean {
        if (commandLongMsg.msgid != msg_command_long.MAVLINK_MSG_ID_COMMAND_LONG) return false

        var processed = true
        when (commandLongMsg.command) {
            MAV_CMD.MAV_CMD_DO_SET_MODE -> setMode(commandLongMsg, aircraft)
            MAV_CMD.MAV_CMD_COMPONENT_ARM_DISARM -> processArmDisarm(commandLongMsg, aircraft, serviceScope)
            MAV_CMD.MAV_CMD_NAV_TAKEOFF -> processTakeoff(commandLongMsg, aircraft)
            MAV_CMD.MAV_CMD_NAV_LAND -> processLanding(commandLongMsg, aircraft)
            // TODO: Unhandled command ID: 521.
            // MAV_CMD.MAV_CMD_REQUEST_CAMERA_INFORMATION -> {}
            else -> processed = false
        }
        return processed
    }

    // https://ardupilot.org/copter/docs/ArduCopter_MAVLink_Messages.html#requestable-messages
    override fun processRequestLong(commandLongMsg: msg_command_long, aircraft: AircraftHandler): Boolean {
        if (commandLongMsg.command != MAV_CMD.MAV_CMD_REQUEST_MESSAGE) return false

        val requestID = commandLongMsg.param1.toInt()
        var processed = true
        when (requestID) {
            msg_autopilot_version.MAVLINK_MSG_ID_AUTOPILOT_VERSION -> sendAutopilotVersion()
            else -> processed = false
        }
        return processed
    }

    fun sendCommandAck(
        messageID: Int,
        result: Int = MAV_RESULT.MAV_RESULT_UNSUPPORTED,
        progress: Int = -1
    ) {
        client.sendMessage(MessageUtils.msgCommandAck(messageID, result, progress))
    }

    fun sendAutopilotAck() {
        sendCommandAck(
            MAV_CMD.MAV_CMD_REQUEST_AUTOPILOT_CAPABILITIES,
            MAV_RESULT.MAV_RESULT_ACCEPTED
        )
    }

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

    fun sendAutopilotVersion() {
        val msg = msg_autopilot_version()
        msg.capabilities =
                    MAV_PROTOCOL_CAPABILITY.MAV_PROTOCOL_CAPABILITY_MISSION_INT.toLong()
                    MAV_PROTOCOL_CAPABILITY.MAV_PROTOCOL_CAPABILITY_COMMAND_INT.toLong() or
                    MAV_PROTOCOL_CAPABILITY.MAV_PROTOCOL_CAPABILITY_SET_ATTITUDE_TARGET.toLong() or
                    MAV_PROTOCOL_CAPABILITY.MAV_PROTOCOL_CAPABILITY_SET_POSITION_TARGET_LOCAL_NED.toLong() or
                    MAV_PROTOCOL_CAPABILITY.MAV_PROTOCOL_CAPABILITY_SET_POSITION_TARGET_GLOBAL_INT.toLong() or
                    MAV_PROTOCOL_CAPABILITY.MAV_PROTOCOL_CAPABILITY_FLIGHT_TERMINATION.toLong() or
                    MAV_PROTOCOL_CAPABILITY.MAV_PROTOCOL_CAPABILITY_MAVLINK2.toLong()
        msg.flight_sw_version = packVersion(
            4,
            18,
            23,
            FIRMWARE_VERSION_TYPE.FIRMWARE_VERSION_TYPE_DEV
        ) // Match SDK version
        // TODO: Identify SDK and Aircraft too
        msg.os_sw_version = 0  // Possibly Android version
        msg.middleware_sw_version = 0  // Possibly WenuLink version
        client.sendMessage(msg)
    }

    fun setMode(commandMsg: msg_command_long, aircraft: AircraftHandler) {
        val requestedMode = commandMsg.param2.toLong()
        logger.d { "FlightMode requested: $requestedMode" }
        if (aircraft.modeTransition(requestedMode))
            sendCommandAck(commandMsg.command, MAV_RESULT.MAV_RESULT_ACCEPTED)
        else
            sendCommandAck(commandMsg.command, MAV_RESULT.MAV_RESULT_DENIED)
    }

    fun processArmDisarm(commandMsg: msg_command_long, aircraft: AircraftHandler, serviceScope: CoroutineScope) {
        val mustArm = commandMsg.param1 == 1F
        val mustDisarm = commandMsg.param1 == 0F
        var resultStr = "NO_ACTION"
        if (mustArm) resultStr = "arm"
        if (mustDisarm) resultStr = "disarm"
        logger.d { "Requesting to $resultStr motors" }

        if (mustArm || mustDisarm) {
            if (mustArm) aircraft.armMotors(true)
            if (mustDisarm) aircraft.armMotors(false)
            sendCommandAck(commandMsg.command, MAV_RESULT.MAV_RESULT_ACCEPTED)
        } else sendCommandAck(commandMsg.command, MAV_RESULT.MAV_RESULT_DENIED)
    }

    fun processTakeoff(commandMsg: msg_command_long, aircraft: AircraftHandler) {
        logger.d { "processTakeoff: $commandMsg" }
        aircraft.takeOff()
        sendCommandAck(commandMsg.command, MAV_RESULT.MAV_RESULT_ACCEPTED)
    }

    fun processLanding(commandMsg: msg_command_long, aircraft: AircraftHandler) {
        logger.d { "processLanding: $commandMsg" }
        aircraft.land()
        sendCommandAck(commandMsg.command, MAV_RESULT.MAV_RESULT_ACCEPTED)
    }

}
