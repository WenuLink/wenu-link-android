package org.WenuLink.controllers

import com.MAVLink.Messages.MAVLinkMessage
import com.MAVLink.common.msg_attitude
import com.MAVLink.common.msg_battery_status
import com.MAVLink.common.msg_command_long
import com.MAVLink.common.msg_control_system_state
import com.MAVLink.common.msg_estimator_status
import com.MAVLink.common.msg_gimbal_device_information
import com.MAVLink.common.msg_global_position_int
import com.MAVLink.common.msg_gps_raw_int
import com.MAVLink.common.msg_gps_status
import com.MAVLink.common.msg_local_position_ned
import com.MAVLink.common.msg_mag_cal_report
import com.MAVLink.common.msg_mission_current
import com.MAVLink.common.msg_nav_controller_output
import com.MAVLink.common.msg_onboard_computer_status
import com.MAVLink.common.msg_power_status
import com.MAVLink.common.msg_raw_rpm
import com.MAVLink.common.msg_rc_channels_raw
import com.MAVLink.common.msg_rc_channels_scaled
import com.MAVLink.common.msg_request_data_stream
import com.MAVLink.common.msg_scaled_pressure
import com.MAVLink.common.msg_sim_state
import com.MAVLink.common.msg_sys_status
import com.MAVLink.common.msg_vfr_hud
import com.MAVLink.common.msg_vibration
import com.MAVLink.enums.MAV_CMD
import com.MAVLink.enums.MAV_DATA_STREAM
import com.MAVLink.enums.MAV_RESULT
import com.MAVLink.minimal.msg_heartbeat
import io.getstream.log.taggedLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import org.WenuLink.adapters.AircraftHandler
import org.WenuLink.adapters.MessageRate
import org.WenuLink.adapters.TelemetryHandler
import org.WenuLink.adapters.AsyncUtils
import org.WenuLink.adapters.MessageUtils
import org.WenuLink.mavlink.MAVLinkClient
import kotlin.math.roundToInt

/**
 * Management class for different MAVLink's microservices message processing.
 * All message processing follows the command protocol service, dealing with message, command, and request.
 *
 * https://mavlink.io/en/services/
 *
 * - ConnectionController: Heartbeat/Connection Protocol
 * - CommandController: Command Protocol
 * - ParameterController: Parameter Protocol
 * - NavigationController: Mission Protocol
 */
class MAVLinkController(
    private val client: MAVLinkClient,
    private val serviceScope: CoroutineScope
) {

    private val logger by taggedLogger("MAVLinkController")
    private var telemetry = TelemetryHandler.getInstance()
    private var aircraft = AircraftHandler.getInstance()
    private var controllers: MutableList<IController> = mutableListOf()
    private var readOnlyMessageRate = false
    private var messageRates: MutableList<MessageRate> = mutableListOf(
        MessageRate( // began with Heartbeat at 1Hz
            msg_heartbeat.MAVLINK_MSG_ID_HEARTBEAT,
            1_000_000L
        )
    )
    private val commandController: CommandController
        get() {
            return controllers.filterIsInstance<CommandController>().first()
        }
    private val connectionController: ConnectionController
        get() {
            return controllers.filterIsInstance<ConnectionController>().first()
        }
    val isReceivingMessages: Boolean
        get() {
            return client.mustReceiveMessages.get()
        }

    /**
     * Data definition based on
     * https://ardupilot.org/dev/docs/mavlink-requesting-data.html#using-srx-parameters
     */
    val availableDataList = mapOf(
        MAV_DATA_STREAM.MAV_DATA_STREAM_RAW_SENSORS to listOf(
//                msg_raw_imu.MAVLINK_MSG_ID_RAW_IMU,
            msg_gps_raw_int.MAVLINK_MSG_ID_GPS_RAW_INT,
            msg_gps_status.MAVLINK_MSG_ID_GPS_STATUS,
//            msg_scaled_imu2.MAVLINK_MSG_ID_SCALED_IMU2,
//            msg_scaled_imu3.MAVLINK_MSG_ID_SCALED_IMU3,
            msg_scaled_pressure.MAVLINK_MSG_ID_SCALED_PRESSURE,
//            msg_scaled_pressure2.MAVLINK_MSG_ID_SCALED_PRESSURE2,
//            msg_scaled_pressure3.MAVLINK_MSG_ID_SCALED_PRESSURE3
        ),
        MAV_DATA_STREAM.MAV_DATA_STREAM_EXTENDED_STATUS to listOf(
            msg_gps_status.MAVLINK_MSG_ID_GPS_STATUS,
            msg_control_system_state.MAVLINK_MSG_ID_CONTROL_SYSTEM_STATE,
            msg_sys_status.MAVLINK_MSG_ID_SYS_STATUS,
            msg_power_status.MAVLINK_MSG_ID_POWER_STATUS,
            // MEMINFO,
            msg_mission_current.MAVLINK_MSG_ID_MISSION_CURRENT,
            msg_gps_raw_int.MAVLINK_MSG_ID_GPS_RAW_INT,
//            msg_gps_rtk.MAVLINK_MSG_ID_GPS_RTK,
//            msg_gps2_raw.MAVLINK_MSG_ID_GPS2_RAW,
//            msg_gps2_rtk.MAVLINK_MSG_ID_GPS2_RTK,
            msg_nav_controller_output.MAVLINK_MSG_ID_NAV_CONTROLLER_OUTPUT,
//            msg_fence_status.MAVLINK_MSG_ID_FENCE_STATUS,
//            msg_position_target_global_int.MAVLINK_MSG_ID_POSITION_TARGET_GLOBAL_INT
        ),

        MAV_DATA_STREAM.MAV_DATA_STREAM_RC_CHANNELS to listOf(
            msg_rc_channels_scaled.MAVLINK_MSG_ID_RC_CHANNELS_SCALED,
            msg_rc_channels_raw.MAVLINK_MSG_ID_RC_CHANNELS_RAW,
//            msg_servo_output_raw.MAVLINK_MSG_ID_SERVO_OUTPUT_RAW
        ),

        MAV_DATA_STREAM.MAV_DATA_STREAM_POSITION to listOf(
            msg_local_position_ned.MAVLINK_MSG_ID_LOCAL_POSITION_NED,
            msg_global_position_int.MAVLINK_MSG_ID_GLOBAL_POSITION_INT
        ),

        MAV_DATA_STREAM.MAV_DATA_STREAM_EXTRA1 to listOf(
            msg_attitude.MAVLINK_MSG_ID_ATTITUDE,
            msg_sim_state.MAVLINK_MSG_ID_SIM_STATE,
        ),

        MAV_DATA_STREAM.MAV_DATA_STREAM_EXTRA2 to listOf(
            msg_vfr_hud.MAVLINK_MSG_ID_VFR_HUD
        ),

        MAV_DATA_STREAM.MAV_DATA_STREAM_EXTRA3 to listOf(
            msg_onboard_computer_status.MAVLINK_MSG_ID_ONBOARD_COMPUTER_STATUS,
//            msg_distance_sensor.MAVLINK_MSG_ID_DISTANCE_SENSOR,
//            msg_terrain_request.MAVLINK_MSG_ID_TERRAIN_REQUEST,
            msg_battery_status.MAVLINK_MSG_ID_BATTERY_STATUS,
//            msg_mount_orientation.MAVLINK_MSG_ID_MOUNT_ORIENTATION,
//            msg_optical_flow.MAVLINK_MSG_ID_OPTICAL_FLOW,
            msg_gimbal_device_information.MAVLINK_MSG_ID_GIMBAL_DEVICE_INFORMATION,
            msg_mag_cal_report.MAVLINK_MSG_ID_MAG_CAL_REPORT,
            msg_estimator_status.MAVLINK_MSG_ID_ESTIMATOR_STATUS,
            msg_vibration.MAVLINK_MSG_ID_VIBRATION,
            msg_raw_rpm.MAVLINK_MSG_ID_RAW_RPM,
        ),

//            MAV_DATA_STREAM.MAV_DATA_STREAM_ALL -> {}
//            MAV_DATA_STREAM.MAV_DATA_STREAM_RAW_CONTROLLER -> null
    )

    init {
        telemetry.registerHandlerScope(serviceScope)
        aircraft.registerHandlerScope(serviceScope)

        // TODO: move to lazy loading?
        controllers += ConnectionController(client)
        controllers += CommandController(client)
        controllers += ParameterController(client)
        controllers += NavigationController(client)
    }

    // https://ardupilot.org/copter/docs/ArduCopter_MAVLink_Messages.html
    // https://mavlink.io/en/services/
    fun processMessage(msg: MAVLinkMessage) {
        // avoid eternal log with the station's heartbeat
        if (!connectionController.isGCSPresent)
            logger.d { "Processing MAVLink message ID: ${msg.msgid}" }
        else {
            if (msg.msgid != 0) logger.d { "Processing MAVLink message ID: ${msg.msgid}" }
        }

        // Process message with registered Controllers
        val processed = controllers.any { it.processMessage(msg, aircraft) }
        if (processed) return

        when (msg.msgid) {
            msg_command_long.MAVLINK_MSG_ID_COMMAND_LONG -> processCommand(msg)
            msg_request_data_stream.MAVLINK_MSG_ID_REQUEST_DATA_STREAM ->
                processDataStreamRequest(msg)

            else -> {
                // Notify if no message ID definition was found in any Controller
                logger.w { "Unhandled message ${msg.name()}" }
                client.sendMessage(MessageUtils.commandAckMsg(msg.msgid))
            }
        }
    }

    fun processCommand(msg: MAVLinkMessage) {
        if (msg.msgid != msg_command_long.MAVLINK_MSG_ID_COMMAND_LONG) return

        val commandMsg = msg as msg_command_long
        // TODO: fix routing
        if (commandMsg.target_system != 0.toShort() && commandMsg.target_system != client.systemID.toShort()) {
            return
        }
        logger.d { "\t - has a command ID: ${commandMsg.command}" }
        val processed = controllers.any { it.processCommand(commandMsg, aircraft, serviceScope) }
        if (processed) return

        when (commandMsg.command) {
            MAV_CMD.MAV_CMD_REQUEST_MESSAGE -> processRequest(commandMsg)
            MAV_CMD.MAV_CMD_SET_MESSAGE_INTERVAL -> processMessageInterval(commandMsg)
            // TODO: Unhandled command ID: 521.
            // MAV_CMD.MAV_CMD_REQUEST_CAMERA_INFORMATION -> {}
            else -> {
                logger.w { "Unhandled command ID: ${commandMsg.name()}" }
                client.sendMessage(MessageUtils.commandAckMsg(commandMsg.command))
            }
        }
    }

    // https://ardupilot.org/copter/docs/ArduCopter_MAVLink_Messages.html#requestable-messages
    fun processRequest(commandMsg: msg_command_long) {
        if (commandMsg.command != MAV_CMD.MAV_CMD_REQUEST_MESSAGE) return

        val requestID = commandMsg.param1.toInt()
        logger.d { "\t - has a request ID: $requestID" }
        val processed = controllers.any { it.processRequest(commandMsg, aircraft) }
        if (processed) return

        when (requestID) {
            // TODO: Unhandled request ID: 280
            // https://mavlink.io/en/messages/common.html#GIMBAL_MANAGER_INFORMATION
            // MAVLINK_MSG_ID_GIMBAL_MANAGER_INFORMATION -> {}
            // TODO: Unhandled request ID: 395 (replaced by 397)
            // TODO: Unhandled request ID: 397
            // https://mavlink.io/en/messages/common.html#COMPONENT_METADATA
            // MAVLINK_MSG_ID_COMPONENT_METADATA -> {}// MAVLink WIP
            // TODO: Unhandled request ID: 259
            // MAVLINK_MSG_ID_CAMERA_INFORMATION // 259
            // TODO: Unhandled request ID: 521
            // MAV_CMD.MAV_CMD_REQUEST_CAMERA_INFORMATION -> {}
            else -> {
                logger.w { "Unhandled request ID: $requestID" }
                client.sendMessage(MessageUtils.requestAckMsg())
            }
        }
    }

    @Synchronized
    fun sendMessages() {
        if (!client.mustProcessMessages()) {
            logger.w { "MAVLink client is not ready!" }
            return
        }

        val currentMicroTime = MessageUtils.getMicroTime()

        if (messageRates.isEmpty()) return

        for (rate in messageRates) {
            if (rate.timeInterval == -1L) continue // skip if deactivated

            // create the message from controllers definitions
            val message = controllers.asSequence()
                .mapNotNull { it.createMessage(rate.messageID, telemetry, aircraft) }
                .firstOrNull()

            if (message == null) {
//                logger.w { "Unable to create message ID: ${rate.messageID}. Deactivating." }
                // Silently deactivate the requested message if not implemented yet
                rate.timeInterval = -1L
                continue
            }

            // if a message is created, check if must sent and update
            if ((currentMicroTime - rate.lastUpdateStamp) >= rate.timeInterval) {
                rate.lastUpdateStamp = currentMicroTime
                client.sendMessage(message)
            }
        }
    }

    fun setMessageRate(messageID: Int, timeInterval: Long): MessageRate {
        var currentRate = messageRates.find { it.messageID == messageID }
//        logger.d { "\tcurrentRate:${currentRate}" }
        if (currentRate == null) {
            currentRate = MessageRate(messageID, timeInterval)
            messageRates.add(currentRate)
        } else currentRate.timeInterval = timeInterval
        return currentRate
    }

    fun processDataStreamRequest(msg: MAVLinkMessage) {
        // https://ardupilot.org/dev/docs/mavlink-requesting-data.html
        // https://mavlink.io/en/messages/common.html#MAV_DATA_STREAM
        if (readOnlyMessageRate) return

        val request = msg as msg_request_data_stream

        val dataList = availableDataList[request.req_stream_id.toInt()]
        if (dataList == null || dataList.isEmpty()) return

        var timeInterval: Int = -1
        if (request.start_stop.toInt() == 1) {
            // requested interval in Hz
            timeInterval = request.req_message_rate
            // Standard 1Hz
            timeInterval = if (timeInterval == 0) 1_000_000
            // Hz to micro seconds if must start
            else ((1.0 / timeInterval.toFloat()) * 1_000_000).roundToInt()
        }

        dataList.forEach { setMessageRate(it, timeInterval.toLong()) }
    }

    fun processMessageInterval(commandMsg: msg_command_long) {
        if (readOnlyMessageRate) return

        logger.d { "processMessageInterval" }
        val mavlinkMsgID = commandMsg.param1.toInt()
        val interval = commandMsg.param2.toLong()  // already in micro seconds
        val newRate = setMessageRate(mavlinkMsgID, interval)
//        logger.d { "\tnew currentRate:${newRate}" }

        client.sendMessage(
            MessageUtils.commandAckMsg(
                commandMsg.command,
                MAV_RESULT.MAV_RESULT_ACCEPTED
            )
        )
    }

    fun notifySystemReady() {
        readOnlyMessageRate = true
    }

    fun isStationConnected(): Boolean = connectionController.isGCSPresent

    fun isTelemetryRunning() = telemetry.isActive()

    suspend fun launchAndWaitTelemetry(timeout: Long = 5000L): Boolean {
        if (telemetry.isActive()) return true
        // Wait for data from SDK
        telemetry.launchTelemetry(true)
        return telemetry.waitTelemetryUp(timeout)
    }

    suspend fun waitTerminateTelemetry(timeout: Long = 5000L): Boolean {
        if (!telemetry.isActive()) return true
        telemetry.launchTelemetry(false)
        return telemetry.waitForTelemetryDown(timeout)
    }

    fun launchListening() {
        if (!telemetry.isActive()) return
        // Start listening for messages
        client.startListening(this@MAVLinkController::processMessage)
    }

    suspend fun launchSending(intervalTime: Long) {
        if (!telemetry.isActive()) return
        // Start sending messages
        client.startSending(intervalTime, this@MAVLinkController::sendMessages)
    }

    suspend fun waitGroundStation(timeout: Long = 5000L): Boolean {
        if (!telemetry.isActive()) return false
        // Send heartbeat and wait for any GCS
        connectionController.sendHeartbeat(aircraft)
        logger.d { "Waiting for GCS." }
        AsyncUtils.waitTimeout(10, timeout, ::isStationConnected)
        logger.i { "GCS found?: ${isStationConnected()}." }
        return isStationConnected()
    }

    fun isSystemReady(): Boolean {
        val navigation = controllers.filterIsInstance<NavigationController>().first()
        val parameter = controllers.filterIsInstance<ParameterController>().first()
        // TODO: Values must persist, once connected QGC does not ask for mission or parameter items
        // TODO: Implement as a setting or app-level variable
        return navigation.wasMissionDownloaded && parameter.wasRequested && aircraft.isHomeSet
    }

    suspend fun waitSystemReady(timeout: Long = 60000L): Boolean {
        // https://docs.qgroundcontrol.com/master/en/qgc-dev-guide/communication_flow.html
        if (!isStationConnected()) return false
        logger.d { "Waiting for MAVLink microservices." }
        val systemReady = AsyncUtils.waitTimeout(1000, timeout, ::isSystemReady)
        if (systemReady) logger.i { "MAVLink microservices initialized." }
        else logger.d { "Unable to check for Mission and Parameter request, possibly already set." }

        return isSystemReady()
    }

    suspend fun waitBoot() {
        // Load and wait parameters for aircraft boot
        val paramController = controllers.filterIsInstance<ParameterController>().first()
        paramController.load()
        while (!paramController.wasInitialized) delay(1000L)
        aircraft.boot()
    }

    fun shutdown() {
        logger.d { "Stop listening and sending processes." }
        client.stopSending()
        client.stopListening()
    }

}
