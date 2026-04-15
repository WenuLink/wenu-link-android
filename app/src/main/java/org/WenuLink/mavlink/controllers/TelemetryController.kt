package org.WenuLink.mavlink.controllers

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
import com.MAVLink.common.msg_radio_status
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
import org.WenuLink.adapters.WenuLinkHandler
import org.WenuLink.adapters.aircraft.MessageRate
import org.WenuLink.mavlink.MAVLinkClient
import org.WenuLink.mavlink.messages.MessageUtils
import org.WenuLink.mavlink.messages.RequestDataStreamMessage
import org.WenuLink.mavlink.messages.SetMessageIntervalCommandLong

class TelemetryController(
    override val client: MAVLinkClient,
    override val handler: WenuLinkHandler
) : IController {
    private val logger by taggedLogger(TelemetryController::class.java.simpleName)

    private var broadcastSuppressed = true
    private val messageRates = mutableListOf(
        MessageRate( // begin with Heartbeat at 1Hz
            msg_heartbeat.MAVLINK_MSG_ID_HEARTBEAT,
            1_000_000L
        )
    )

    /**
     * Data definition based on
     * https://ardupilot.org/dev/docs/mavlink-requesting-data.html#using-srx-parameters
     */
    private val availableDataList = mapOf(
        MAV_DATA_STREAM.MAV_DATA_STREAM_RAW_SENSORS to listOf(
//                msg_raw_imu.MAVLINK_MSG_ID_RAW_IMU,
            msg_gps_raw_int.MAVLINK_MSG_ID_GPS_RAW_INT,
            msg_gps_status.MAVLINK_MSG_ID_GPS_STATUS,
//            msg_scaled_imu2.MAVLINK_MSG_ID_SCALED_IMU2,
//            msg_scaled_imu3.MAVLINK_MSG_ID_SCALED_IMU3,
            msg_scaled_pressure.MAVLINK_MSG_ID_SCALED_PRESSURE
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
            msg_nav_controller_output.MAVLINK_MSG_ID_NAV_CONTROLLER_OUTPUT
//            msg_fence_status.MAVLINK_MSG_ID_FENCE_STATUS,
//            msg_position_target_global_int.MAVLINK_MSG_ID_POSITION_TARGET_GLOBAL_INT
        ),
        MAV_DATA_STREAM.MAV_DATA_STREAM_RC_CHANNELS to listOf(
            msg_rc_channels_scaled.MAVLINK_MSG_ID_RC_CHANNELS_SCALED,
            msg_rc_channels_raw.MAVLINK_MSG_ID_RC_CHANNELS_RAW,
//            msg_servo_output_raw.MAVLINK_MSG_ID_SERVO_OUTPUT_RAW
            msg_radio_status.MAVLINK_MSG_ID_RADIO_STATUS
        ),
        MAV_DATA_STREAM.MAV_DATA_STREAM_POSITION to listOf(
            msg_local_position_ned.MAVLINK_MSG_ID_LOCAL_POSITION_NED,
            msg_global_position_int.MAVLINK_MSG_ID_GLOBAL_POSITION_INT
        ),
        MAV_DATA_STREAM.MAV_DATA_STREAM_EXTRA1 to listOf(
            msg_attitude.MAVLINK_MSG_ID_ATTITUDE,
            msg_sim_state.MAVLINK_MSG_ID_SIM_STATE
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
            msg_raw_rpm.MAVLINK_MSG_ID_RAW_RPM
        )
//            MAV_DATA_STREAM.MAV_DATA_STREAM_ALL -> {}
//            MAV_DATA_STREAM.MAV_DATA_STREAM_RAW_CONTROLLER -> null
    )

    private val messageRegistry: Map<Int, (MAVLinkMessage) -> Unit> = mapOf(
        msg_request_data_stream.MAVLINK_MSG_ID_REQUEST_DATA_STREAM to ::processDataStreamRequest
    )

    private val commandLongRegistry: Map<Int, (msg_command_long) -> Unit> = mapOf(
        MAV_CMD.MAV_CMD_SET_MESSAGE_INTERVAL to ::processMessageInterval
    )

    override fun processMessage(msg: MAVLinkMessage): Boolean {
        messageRegistry[msg.msgid]?.invoke(msg) ?: return false
        return true
    }

    override fun processCommandLong(commandLongMsg: msg_command_long): Boolean {
        commandLongRegistry[commandLongMsg.command]?.invoke(commandLongMsg) ?: return false
        return true
    }

    fun sendMessages(controllers: List<IController>) {
        if (!client.mustProcessMessages()) {
            logger.w { "MAVLink client is not ready!" }
            return
        }

        val currentMicroTime = MessageUtils.getMicroTime()
        for (rate in messageRates) {
            if (broadcastSuppressed && rate.messageID != 0) {
                // skip non-HEARTBEAT messages if broadcast is suppressed
                continue
            }
            if (rate.microSecondsInterval == -1L) {
                // skip if deactivated
                continue
            }
            if ((currentMicroTime - rate.lastUpdateStamp) <= rate.microSecondsInterval) {
                // skip if not time-ready
                continue
            }

            // create the message from controllers definitions
            val message = controllers.asSequence()
                .mapNotNull { it.createMessage(rate.messageID) }
                .firstOrNull()

            if (message == null) {
                // logger.w { "Unable to create message ID: ${rate.messageID}. Deactivating." }
                // Silently deactivate the requested message if not implemented yet
                rate.microSecondsInterval = -1L
                continue
            }

            // send message and update timestamp
            client.sendMessage(message)
            rate.lastUpdateStamp = currentMicroTime
        }
    }

    @Synchronized
    fun setMessageRate(messageID: Int, microSecondsInterval: Long): MessageRate {
        var currentRate = messageRates.find { it.messageID == messageID }
        if (currentRate == null) {
            currentRate = MessageRate(messageID, microSecondsInterval)
            messageRates.add(currentRate)
        } else {
            currentRate.microSecondsInterval = microSecondsInterval
        }
        return currentRate
    }

    @Synchronized
    fun processDataStreamRequest(msg: MAVLinkMessage) {
        // https://ardupilot.org/dev/docs/mavlink-requesting-data.html
        // https://mavlink.io/en/messages/common.html#MAV_DATA_STREAM
        val params = RequestDataStreamMessage(msg as msg_request_data_stream)
        val dataList = availableDataList[params.streamId] ?: return

        dataList.forEach {
            setMessageRate(it, params.toIntervalUs())
        }
    }

    @Synchronized
    fun processMessageInterval(commandMsg: msg_command_long) {
        logger.d { "processMessageInterval" }

        val params = SetMessageIntervalCommandLong(commandMsg)
        setMessageRate(params.messageId, params.intervalUs)

        client.sendMessage(
            MessageUtils.msgCommandAck(
                commandMsg.command,
                MAV_RESULT.MAV_RESULT_ACCEPTED
            )
        )
    }

    fun stopBroadcast() {
        broadcastSuppressed = true
    }

    fun startBroadcast() {
        broadcastSuppressed = false
    }
}
