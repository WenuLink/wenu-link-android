package org.WenuLink.mavlink.controllers

import com.MAVLink.Messages.MAVLinkMessage
import com.MAVLink.common.msg_autopilot_version
import com.MAVLink.common.msg_command_long
import com.MAVLink.enums.FIRMWARE_VERSION_TYPE
import com.MAVLink.enums.MAV_CMD
import com.MAVLink.enums.MAV_PROTOCOL_CAPABILITY
import com.MAVLink.enums.MAV_RESULT
import io.getstream.log.taggedLogger
import org.WenuLink.adapters.MessageUtils
import org.WenuLink.adapters.RequestMissionAction
import org.WenuLink.adapters.RequestTakeoff
import org.WenuLink.adapters.WenuLinkCommand
import org.WenuLink.adapters.WenuLinkHandler
import org.WenuLink.adapters.aircraft.ArduCopterFlightMode
import org.WenuLink.adapters.aircraft.ArmCommand
import org.WenuLink.adapters.aircraft.DisarmCommand
import org.WenuLink.adapters.mission.DelayAction
import org.WenuLink.adapters.mission.RotateAction
import org.WenuLink.mavlink.MAVLinkClient

/**
 * MAVLinkController class to deal with the command service and related MAVLink messages.
 *
 * https://mavlink.io/en/services/command.html
 *
 * https://ardupilot.org/copter/docs/ArduCopter_MAVLink_Messages.html#incoming-commands
 */
class CommandController(override var client: MAVLinkClient, override val handler: WenuLinkHandler) :
    IController {
    private val logger by taggedLogger(CommandController::class.java.simpleName)

    private val autopilotCapabilities = listOf(
        MAV_PROTOCOL_CAPABILITY.MAV_PROTOCOL_CAPABILITY_MISSION_INT,
        MAV_PROTOCOL_CAPABILITY.MAV_PROTOCOL_CAPABILITY_COMMAND_INT,
        MAV_PROTOCOL_CAPABILITY.MAV_PROTOCOL_CAPABILITY_SET_ATTITUDE_TARGET,
        MAV_PROTOCOL_CAPABILITY.MAV_PROTOCOL_CAPABILITY_SET_POSITION_TARGET_LOCAL_NED,
        MAV_PROTOCOL_CAPABILITY.MAV_PROTOCOL_CAPABILITY_SET_POSITION_TARGET_GLOBAL_INT,
        MAV_PROTOCOL_CAPABILITY.MAV_PROTOCOL_CAPABILITY_FLIGHT_TERMINATION,
        MAV_PROTOCOL_CAPABILITY.MAV_PROTOCOL_CAPABILITY_MAVLINK2
    ).fold(0L) { acc, value -> acc or value.toLong() }

    override fun processMessage(msg: MAVLinkMessage): Boolean {
        when (msg.msgid) {
            msg_autopilot_version.MAVLINK_MSG_ID_AUTOPILOT_VERSION -> sendAutopilotAck()
            else -> return false
        }
        return true
    }

    override fun processCommandLong(commandLongMsg: msg_command_long): Boolean {
        if (commandLongMsg.msgid != msg_command_long.MAVLINK_MSG_ID_COMMAND_LONG) return false

        when (commandLongMsg.command) {
            MAV_CMD.MAV_CMD_DO_SET_MODE -> setMode(commandLongMsg)
            MAV_CMD.MAV_CMD_COMPONENT_ARM_DISARM -> processArmDisarm(commandLongMsg)
            MAV_CMD.MAV_CMD_NAV_TAKEOFF -> processTakeoff(commandLongMsg)
            MAV_CMD.MAV_CMD_NAV_LAND -> processLanding(commandLongMsg)
            MAV_CMD.MAV_CMD_NAV_RETURN_TO_LAUNCH -> processReturn(commandLongMsg)
            MAV_CMD.MAV_CMD_NAV_DELAY -> processDelay(commandLongMsg)
            MAV_CMD.MAV_CMD_CONDITION_YAW -> processYaw(commandLongMsg)
            else -> return false
        }
        return true
    }

    // https://ardupilot.org/copter/docs/ArduCopter_MAVLink_Messages.html#requestable-messages
    override fun processRequestLong(commandLongMsg: msg_command_long): Boolean {
        if (commandLongMsg.command != MAV_CMD.MAV_CMD_REQUEST_MESSAGE) return false

        val requestID = commandLongMsg.param1.toInt()
        when (requestID) {
            msg_autopilot_version.MAVLINK_MSG_ID_AUTOPILOT_VERSION -> sendAutopilotVersion()
            else -> return false
        }
        return true
    }

    fun sendCommandAck(
        messageID: Int,
        result: Int = MAV_RESULT.MAV_RESULT_UNSUPPORTED,
        progress: Int = -1
    ) = client.sendMessage(MessageUtils.msgCommandAck(messageID, result, progress))

    fun sendAutopilotAck() = sendCommandAck(
        MAV_CMD.MAV_CMD_REQUEST_AUTOPILOT_CAPABILITIES,
        MAV_RESULT.MAV_RESULT_ACCEPTED
    )

    fun sendAutopilotVersion() = client.sendMessage(
        msg_autopilot_version().apply {
            capabilities = autopilotCapabilities
            flight_sw_version = MessageUtils.packVersion(
                4,
                18,
                23,
                FIRMWARE_VERSION_TYPE.FIRMWARE_VERSION_TYPE_DEV
            ) // Match SDK version
            // TODO: Identify SDK and Aircraft too
            os_sw_version = 0 // Possibly Android version
            middleware_sw_version = 0 // Possibly WenuLink version
        }
    )

    fun setMode(commandMsg: msg_command_long) {
        val requestedMode = commandMsg.param2.toLong()
        logger.d { "FlightMode requested: $requestedMode" }
        val customMode = ArduCopterFlightMode.from(requestedMode)

        if (customMode == null) {
            sendCommandAck(commandMsg.command, MAV_RESULT.MAV_RESULT_DENIED)
            return
        }

        if (handler.aircraft.requestMode(customMode).isOk) {
            sendCommandAck(commandMsg.command, MAV_RESULT.MAV_RESULT_ACCEPTED)
        } else {
            sendCommandAck(commandMsg.command, MAV_RESULT.MAV_RESULT_DENIED)
        }
    }

    fun processArmDisarm(commandMsg: msg_command_long) {
        val action = when (commandMsg.param1) {
            1f -> true
            0f -> false
            else -> null
        }

        if (action == null) {
            logger.d { "Invalid arm/disarm request: ${commandMsg.param1}" }
            sendCommandAck(commandMsg.command, MAV_RESULT.MAV_RESULT_DENIED)
            return
        }

        logger.d { "Requesting to ${if (action) "arm" else "disarm"} motors" }

        val command = if (action) ArmCommand() else DisarmCommand()
        handler.dispatchCommand(WenuLinkCommand.Aircraft(command)) { result ->
            logger.d { "processTakeoff: $result" }
        }

        sendCommandAck(commandMsg.command, MAV_RESULT.MAV_RESULT_ACCEPTED)
    }

    fun processTakeoff(commandMsg: msg_command_long) {
        logger.d { "processTakeoff: $commandMsg" }
        handler.dispatchCommand(WenuLinkCommand.Request(RequestTakeoff())) { result ->
            logger.d { "processTakeoff: $result" }
        }
        sendCommandAck(commandMsg.command, MAV_RESULT.MAV_RESULT_ACCEPTED)
    }

    fun processLanding(commandMsg: msg_command_long) {
        logger.d { "processLanding: $commandMsg" }
        if (handler.aircraft.requestMode(ArduCopterFlightMode.LAND).isOk) {
            sendCommandAck(commandMsg.command, MAV_RESULT.MAV_RESULT_ACCEPTED)
        } else {
            sendCommandAck(commandMsg.command, MAV_RESULT.MAV_RESULT_DENIED)
        }
    }

    fun processReturn(commandMsg: msg_command_long) {
        logger.d { "processReturn: $commandMsg" }
        if (handler.aircraft.requestMode(ArduCopterFlightMode.RTL).isOk) {
            sendCommandAck(commandMsg.command, MAV_RESULT.MAV_RESULT_ACCEPTED)
        } else {
            sendCommandAck(commandMsg.command, MAV_RESULT.MAV_RESULT_DENIED)
        }
    }

    fun processDelay(commandMsg: msg_command_long) {
        logger.d { "processDelay: $commandMsg" }
        handler.dispatchCommand(
            WenuLinkCommand.Request(
                RequestMissionAction(DelayAction.fromCommandLong(commandMsg))
            )
        ) { result ->
            logger.d { "processDelay: $result" }
        }
        sendCommandAck(commandMsg.command, MAV_RESULT.MAV_RESULT_ACCEPTED)
    }

    fun processYaw(commandMsg: msg_command_long) {
        logger.d { "processYaw: $commandMsg" }
        handler.dispatchCommand(
            WenuLinkCommand.Request(
                RequestMissionAction(RotateAction.fromCommandLong(commandMsg))
            )
        ) { result ->
            logger.d { "processYaw: $result" }
        }
        sendCommandAck(commandMsg.command, MAV_RESULT.MAV_RESULT_ACCEPTED)
    }
}
