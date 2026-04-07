package org.WenuLink.mavlink

import com.MAVLink.Messages.MAVLinkMessage
import com.MAVLink.common.msg_command_int
import com.MAVLink.common.msg_command_long
import com.MAVLink.common.msg_home_position
import com.MAVLink.enums.MAV_CMD
import io.getstream.log.taggedLogger
import org.WenuLink.adapters.AsyncUtils
import org.WenuLink.adapters.MessageUtils
import org.WenuLink.adapters.WenuLinkHandler
import org.WenuLink.adapters.camera.ImageMetadata
import org.WenuLink.mavlink.controllers.CameraController
import org.WenuLink.mavlink.controllers.CommandController
import org.WenuLink.mavlink.controllers.ConnectionController
import org.WenuLink.mavlink.controllers.IController
import org.WenuLink.mavlink.controllers.NavigationController
import org.WenuLink.mavlink.controllers.ParameterController
import org.WenuLink.mavlink.controllers.TelemetryController

/**
 * Management class for different MAVLink's microservices message processing.
 * All message processing follows the command protocol service, dealing with message, command, and
 * request.
 *
 * https://mavlink.io/en/services/
 *
 * - ConnectionController: Heartbeat/Connection Protocol
 * - CommandController: Command Protocol
 * - ParameterController: Parameter Protocol
 * - NavigationController: Mission Protocol
 * - TelemetryController: Message Protocol
 */
class MAVLinkController(private val handler: WenuLinkHandler) {
    private val logger by taggedLogger(MAVLinkController::class.java.simpleName)
    private val controllers: MutableList<IController> = mutableListOf()
    private lateinit var client: MAVLinkClient
    private val connectionController: ConnectionController
        get() = controllers.filterIsInstance<ConnectionController>().first()

    private val navigationController: NavigationController
        get() = controllers.filterIsInstance<NavigationController>().first()

    private val parameterController: ParameterController
        get() = controllers.filterIsInstance<ParameterController>().first()

    private val telemetryController: TelemetryController
        get() = controllers.filterIsInstance<TelemetryController>().first()

    private val cameraController: CameraController
        get() = controllers.filterIsInstance<CameraController>().first()

    fun attachClient(client: MAVLinkClient) {
        this.client = client
        // Initialize controllers
        controllers.clear()
        controllers += ConnectionController(client)
        controllers += CommandController(client)
        controllers += ParameterController(client)
        controllers += NavigationController(client)
        controllers += TelemetryController(client)
        controllers += CameraController(client, telemetryController::setMessageRate)
        registerCameraHooks()
    }

    // https://ardupilot.org/copter/docs/ArduCopter_MAVLink_Messages.html
    // https://mavlink.io/en/services/
    fun processMessage(msg: MAVLinkMessage) {
        // avoid eternal log with the station's heartbeat
        if (!connectionController.isGCSPresent || msg.msgid != 0) {
            logger.d { "Processing message: ${msg.name()}" }
        }

        // Process message with registered Controllers
        val processed = controllers.any { it.processMessage(msg, handler) }
        if (processed) return

        when (msg.msgid) {
            msg_command_long.MAVLINK_MSG_ID_COMMAND_LONG -> processCommandLong(msg)

            msg_command_int.MAVLINK_MSG_ID_COMMAND_INT -> processCommandInt(msg)

            // Notify if no message ID definition was found in any Controller
            else -> unhandledCommand(msg.name(), msg.msgid)
        }
    }

    // TODO: fix routing
    fun isTargetSystem(msgTargetSystem: Int) =
        msgTargetSystem == 0 || msgTargetSystem == client.systemID

    fun processCommandLong(msg: MAVLinkMessage) {
        if (msg.msgid != msg_command_long.MAVLINK_MSG_ID_COMMAND_LONG) return

        val commandMsg = msg as msg_command_long
        logger.d { "\t- COMMAND_LONG ID: ${commandMsg.command}" }

        if (!isTargetSystem(commandMsg.target_system.toInt())) return

        val processed = controllers.any {
            it.processCommandLong(commandMsg, handler)
        }
        if (processed) return

        when (commandMsg.command) {
            MAV_CMD.MAV_CMD_REQUEST_MESSAGE -> processRequestLong(commandMsg)

            // TODO: Unhandled command ID: 521.
            // MAV_CMD.MAV_CMD_REQUEST_CAMERA_INFORMATION -> {}
            else -> unhandledCommand("COMMAND_LONG", commandMsg.command)
        }
    }

    fun processCommandInt(msg: MAVLinkMessage) {
        if (msg.msgid != msg_command_int.MAVLINK_MSG_ID_COMMAND_INT) return

        val commandMsg = msg as msg_command_int
        logger.d { "\t- COMMAND_INT ID: ${commandMsg.command}" }

        if (!isTargetSystem(commandMsg.target_system.toInt())) return

        val processed = controllers.any {
            it.processCommandInt(commandMsg, handler)
        }
        if (processed) return

        when (commandMsg.command) {
            MAV_CMD.MAV_CMD_REQUEST_MESSAGE -> processRequestInt(commandMsg)
            else -> unhandledCommand("COMMAND_INT", commandMsg.command)
        }
    }

    // https://ardupilot.org/copter/docs/ArduCopter_MAVLink_Messages.html#requestable-messages
    fun processRequestLong(commandMsg: msg_command_long) {
        if (commandMsg.command != MAV_CMD.MAV_CMD_REQUEST_MESSAGE) return

        val requestID = commandMsg.param1.toInt()
        logger.d { "\t- REQUEST_LONG ID: $requestID" }
        val processed = controllers.any { it.processRequestLong(commandMsg, handler) }
        if (processed) return

        when (requestID) {
            // TODO: Unhandled request ID: 280
            // https://mavlink.io/en/messages/common.html#GIMBAL_MANAGER_INFORMATION
            // MAVLINK_MSG_ID_GIMBAL_MANAGER_INFORMATION -> {}
            // TODO: Unhandled request ID: 395 (replaced by 397)
            // TODO: Unhandled request ID: 397
            // https://mavlink.io/en/messages/common.html#COMPONENT_METADATA
            // MAVLINK_MSG_ID_COMPONENT_METADATA -> {}// MAVLink WIP
            else -> unhandledRequest("REQUEST_LONG", requestID)
        }
    }

    fun processRequestInt(commandMsg: msg_command_int) {
        if (commandMsg.command != MAV_CMD.MAV_CMD_REQUEST_MESSAGE) return

        val requestID = commandMsg.param1.toInt()
        logger.d { "\t- REQUEST_INT ID: $requestID" }
        val processed = controllers.any { it.processRequestInt(commandMsg, handler) }
        if (processed) return

        when (requestID) {
            else -> unhandledRequest("REQUEST_INT", requestID)
        }
    }

    @Synchronized
    fun sendMessages() {
        telemetryController.sendMessages(controllers, handler)
    }

    fun notifySystemReady() {
        telemetryController.startBroadcast()
    }

    fun isStationConnected(): Boolean = connectionController.isGCSPresent

    suspend fun waitGroundStation(timeout: Long = 5000L): Boolean {
        // prevent to send data before initialization
        telemetryController.stopBroadcast()
        // Wait for GCS heartbeat
        logger.d { "Waiting for GCS." }
        AsyncUtils.waitTimeout(10, timeout, ::isStationConnected)
        logger.i { "GCS found?: ${isStationConnected()}." }
        return isStationConnected()
    }

    suspend fun waitServicesRequest(timeout: Long = 30000L): Boolean {
        // https://docs.qgroundcontrol.com/master/en/qgc-dev-guide/communication_flow.html
        if (!isStationConnected()) return false

        logger.d { "Waiting for Parameters request." }
        if (!AsyncUtils.waitTimeout(1000, timeout) { parameterController.wasRequested }) {
            // If PARAM_REQUEST_LIST is not sent (timeout), a service restart is assumed
            logger.d { "Unable to check for Parameters request, possibly already requested." }
            notifySystemReady()
            return true
        }

        // Check for mission items after request parameters
        logger.d { "Waiting for Mission items request." }
        if (!AsyncUtils.waitTimeout(1000, timeout) { navigationController.wasRequested }) {
            logger.d { "Unable to check for Mission request, possibly already set." }
        }

        // unlock even after timeout
        notifySystemReady()
        return true
    }

    suspend fun waitHomePosition(): Boolean {
        // Wait for home position to send GPS_GLOBAL_ORIGIN and periodic HOME_POSITION
        val isHomeSet = handler.aircraft.waitHomeSet(360000) // 5min

        if (!isHomeSet) return false

        // Send origin coordinates
        client.sendMessage(navigationController.msgGpsGlobalOrigin(handler)!!)
        // update message rate of HOME_POSITION
        telemetryController.setMessageRate(
            msg_home_position.MAVLINK_MSG_ID_HOME_POSITION,
            1_000_000L
        )
        return true
    }

    private fun unhandledCommand(label: String, id: Int) {
        logger.w { "Unhandled $label ID: $id" }
        client.sendMessage(MessageUtils.msgCommandAck(id))
    }

    private fun unhandledRequest(label: String, id: Int) {
        logger.w { "Unhandled $label ID: $id" }
        client.sendMessage(MessageUtils.msgRequestAck())
    }

    private fun registerCameraHooks() {
        handler.onImageCaptured = { cameraId, seqIndex ->
            val telemetry = handler.aircraft.telemetry.getData()
            if (telemetry != null) {
                client.sendMessage(
                    cameraController.msgImageCaptured(
                        ImageMetadata(seqIndex, true, cameraId, telemetry)
                    )
                )
            }
        }
    }
}
