package org.WenuLink.mavlink.controllers

import com.MAVLink.Messages.MAVLinkMessage
import com.MAVLink.common.msg_altitude
import com.MAVLink.common.msg_attitude
import com.MAVLink.common.msg_battery_status
import com.MAVLink.common.msg_extended_sys_state
import com.MAVLink.common.msg_mag_cal_report
import com.MAVLink.common.msg_power_status
import com.MAVLink.common.msg_radio_status
import com.MAVLink.common.msg_sys_status
import com.MAVLink.common.msg_system_time
import com.MAVLink.common.msg_timesync
import com.MAVLink.common.msg_vfr_hud
import com.MAVLink.common.msg_vibration
import com.MAVLink.enums.MAG_CAL_STATUS
import com.MAVLink.enums.MAV_AUTOPILOT
import com.MAVLink.enums.MAV_SENSOR_ORIENTATION
import com.MAVLink.enums.MAV_SYS_STATUS_SENSOR
import com.MAVLink.enums.MAV_TYPE
import com.MAVLink.enums.MAV_VTOL_STATE
import com.MAVLink.minimal.msg_heartbeat
import kotlin.math.roundToInt
import kotlin.math.sqrt
import org.WenuLink.adapters.MessageUtils
import org.WenuLink.adapters.WenuLinkHandler
import org.WenuLink.adapters.aircraft.BatteryMapper
import org.WenuLink.mavlink.MAVLinkClient

/**
 * MAVLinkController class to deal with the heartbeat/connection service and related MAVLink
 * messages.
 *
 * https://mavlink.io/en/services/heartbeat.html
 */
class ConnectionController(
    override val client: MAVLinkClient,
    override val handler: WenuLinkHandler
) : IController {
    private var gcsLastTimestamp = 0L
    val isGCSPresent: Boolean
        get() {
            val hasGCS = gcsLastTimestamp > 0
            val isRecent = (System.currentTimeMillis() - gcsLastTimestamp) < 5000L
            return hasGCS && isRecent
        }

    // TODO: update with sensors from AircraftHandler
    val sensorsPresent = MAV_SYS_STATUS_SENSOR.MAV_SYS_STATUS_SENSOR_3D_GYRO or
        MAV_SYS_STATUS_SENSOR.MAV_SYS_STATUS_SENSOR_3D_ACCEL or
        MAV_SYS_STATUS_SENSOR.MAV_SYS_STATUS_SENSOR_3D_MAG or
        MAV_SYS_STATUS_SENSOR.MAV_SYS_STATUS_SENSOR_ABSOLUTE_PRESSURE or
        MAV_SYS_STATUS_SENSOR.MAV_SYS_STATUS_SENSOR_ANGULAR_RATE_CONTROL or
        MAV_SYS_STATUS_SENSOR.MAV_SYS_STATUS_SENSOR_ATTITUDE_STABILIZATION or
        MAV_SYS_STATUS_SENSOR.MAV_SYS_STATUS_SENSOR_YAW_POSITION or
        MAV_SYS_STATUS_SENSOR.MAV_SYS_STATUS_SENSOR_Z_ALTITUDE_CONTROL or
        MAV_SYS_STATUS_SENSOR.MAV_SYS_STATUS_SENSOR_XY_POSITION_CONTROL or
        MAV_SYS_STATUS_SENSOR.MAV_SYS_STATUS_SENSOR_RC_RECEIVER or
        MAV_SYS_STATUS_SENSOR.MAV_SYS_STATUS_GEOFENCE or
        MAV_SYS_STATUS_SENSOR.MAV_SYS_STATUS_AHRS or
        MAV_SYS_STATUS_SENSOR.MAV_SYS_STATUS_TERRAIN or
        MAV_SYS_STATUS_SENSOR.MAV_SYS_STATUS_LOGGING or
        MAV_SYS_STATUS_SENSOR.MAV_SYS_STATUS_SENSOR_BATTERY or
        MAV_SYS_STATUS_SENSOR.MAV_SYS_STATUS_PREARM_CHECK or
        MAV_SYS_STATUS_SENSOR.MAV_SYS_STATUS_SENSOR_PROPULSION

    // TODO: Update accordingly?
    val sensorsEnabled = sensorsPresent and
        MAV_SYS_STATUS_SENSOR.MAV_SYS_STATUS_SENSOR_Z_ALTITUDE_CONTROL.inv() and
        MAV_SYS_STATUS_SENSOR.MAV_SYS_STATUS_SENSOR_XY_POSITION_CONTROL.inv() and
        MAV_SYS_STATUS_SENSOR.MAV_SYS_STATUS_GEOFENCE.inv() and
        MAV_SYS_STATUS_SENSOR.MAV_SYS_STATUS_LOGGING.inv()

    val sensorsHealth = (
        sensorsPresent or
            MAV_SYS_STATUS_SENSOR.MAV_SYS_STATUS_SENSOR_GPS or
            MAV_SYS_STATUS_SENSOR.MAV_SYS_STATUS_SENSOR_PROXIMITY
        ) and
        MAV_SYS_STATUS_SENSOR.MAV_SYS_STATUS_SENSOR_Z_ALTITUDE_CONTROL.inv() and
        MAV_SYS_STATUS_SENSOR.MAV_SYS_STATUS_SENSOR_XY_POSITION_CONTROL.inv()

    override fun processMessage(msg: MAVLinkMessage): Boolean {
        when (msg.msgid) {
            msg_heartbeat.MAVLINK_MSG_ID_HEARTBEAT -> processHeartbeatGCS()
            msg_system_time.MAVLINK_MSG_ID_SYSTEM_TIME -> processSystemTime(msg)
            msg_timesync.MAVLINK_MSG_ID_TIMESYNC -> processTimeSync(msg)
            else -> return false
        }
        return true
    }

    override fun createMessage(messageID: Int): MAVLinkMessage? = when (messageID) {
        msg_heartbeat.MAVLINK_MSG_ID_HEARTBEAT -> msgHeartbeat()

        msg_sys_status.MAVLINK_MSG_ID_SYS_STATUS -> msgSysStatus()

        msg_attitude.MAVLINK_MSG_ID_ATTITUDE -> msgAttitude()

        msg_altitude.MAVLINK_MSG_ID_ALTITUDE -> msgAltitude()

        msg_vibration.MAVLINK_MSG_ID_VIBRATION -> msgVibration()

        msg_vfr_hud.MAVLINK_MSG_ID_VFR_HUD -> msgHUD()

        msg_radio_status.MAVLINK_MSG_ID_RADIO_STATUS -> msgRadioStatus()

        msg_power_status.MAVLINK_MSG_ID_POWER_STATUS -> msgPowerStatus()

        msg_battery_status.MAVLINK_MSG_ID_BATTERY_STATUS -> msgBatteryStatus()

        msg_extended_sys_state.MAVLINK_MSG_ID_EXTENDED_SYS_STATE -> msgExtendedSys()

        //            msg_mag_cal_report.MAVLINK_MSG_ID_MAG_CAL_REPORT -> msgMagCal()
        else -> null
    }

    fun processHeartbeatGCS() {
        gcsLastTimestamp = System.currentTimeMillis()
    }

    fun msgTimeSync(): MAVLinkMessage {
        val msg = msg_timesync()
        msg.tc1 = MessageUtils.getMicroTime()
        return msg
    }

    fun processTimeSync(msg: MAVLinkMessage) {
        val outMsg = msgTimeSync() as msg_timesync
        outMsg.ts1 = (msg as msg_timesync).ts1
        outMsg.target_system = msg.sysid.toShort()
        outMsg.target_component = msg.compid.toShort()
        client.sendMessage(outMsg)
    }

    fun msgSystemTime(): MAVLinkMessage {
        val currentStamp = System.currentTimeMillis()
        val msg = msg_system_time()
        msg.time_unix_usec = currentStamp * 1000
        msg.time_boot_ms = currentStamp - handler.startTimestamp
        return msg
    }

    fun processSystemTime(msg: MAVLinkMessage) = client.sendMessage(msgSystemTime())

    fun msgHeartbeat(): MAVLinkMessage {
        val heartbeat = msg_heartbeat()
        heartbeat.type = MAV_TYPE.MAV_TYPE_QUADROTOR.toShort()
        heartbeat.autopilot = MAV_AUTOPILOT.MAV_AUTOPILOT_ARDUPILOTMEGA.toShort()
        heartbeat.system_status = handler.aircraftState.mavlink.toShort()
        heartbeat.mavlink_version = 3
        // mode definition
        // For base mode logic, see Copter::sendHeartBeat() in ArduCopter/GCS_Mavlink.cpp
        heartbeat.base_mode = handler.aircraftState.modeFlag.toShort()
        heartbeat.custom_mode = handler.aircraftState.flightMode.mode
        return heartbeat
    }

    fun msgSysStatus(): MAVLinkMessage {
        val battery = BatteryMapper.toMavlink(handler.aircraft.telemetry.getAircraftBattery())
        val msg = msg_sys_status()

//        if (!aircraft.preArmCheckOk) sensorsHealth = sensorsHealth and
//                MAV_SYS_STATUS_SENSOR.MAV_SYS_STATUS_PREARM_CHECK.inv()

        msg.onboard_control_sensors_present = sensorsPresent.toLong()
        msg.onboard_control_sensors_enabled = sensorsEnabled.toLong()
        msg.onboard_control_sensors_health = sensorsHealth.toLong()

        msg.battery_remaining = battery.batteryRemaining
        msg.voltage_battery = battery.voltagesBattery
        msg.current_battery = battery.currentBatteryRaw
//        client.sendMessage(msg)
        return msg
    }

    fun msgAttitude(): MAVLinkMessage? {
        val telemetryData = handler.aircraft.telemetry.getData() ?: return null

        val msg = msg_attitude()
        // TODO: this next line causes an exception
        // msg.time_boot_ms = getTimestampMilliseconds();
        msg.roll = (telemetryData.roll * Math.PI / 180).toFloat()
        msg.pitch = (telemetryData.pitch * Math.PI / 180).toFloat()
        msg.yaw = (telemetryData.yaw * Math.PI / 180).toFloat()
        // TODO msg.rollspeed = 0;
        // TODO msg.pitchspeed = 0;
        // TODO msg.yawspeed = 0;
//        client.sendMessage(msg)
        return msg
    }

    fun msgAltitude(): MAVLinkMessage? {
        val data = handler.aircraft.telemetry.getData() ?: return null
        val msg = msg_altitude()
        msg.altitude_relative = data.altitude
//        client.sendMessage(msg)
        return msg
    }

    fun msgVibration(): MAVLinkMessage {
//        client.sendMessage(msg_vibration())
        return msg_vibration()
    }

    fun msgHUD(): MAVLinkMessage? {
        val telemetryData = handler.aircraft.telemetry.getData() ?: return null
        val rcData = handler.aircraft.telemetry.getRCData()?.toMAVLink() ?: return null
        val msg = msg_vfr_hud()
        // Mavlink: Current airspeed in m/s
        // DJI: unclear whether getState() returns airspeed or groundspeed
        msg.airspeed = sqrt(
            telemetryData.velocityX * telemetryData.velocityX +
                telemetryData.velocityY * telemetryData.velocityY
        )
        // Mavlink: Current ground speed in m/s. For now, just echoing airspeed.
        msg.groundspeed = msg.airspeed
        // yaw angle
        var heading = telemetryData.yaw
        if (heading < 0) heading += 360
        msg.heading = heading.toInt().toShort()
        // vertical info
        msg.throttle = rcData.throttleSetting
        msg.alt = -telemetryData.altitude
        // Mavlink: Current climb rate in meters/second
        // DJI: m/s, positive values down
        msg.climb = -telemetryData.velocityZ
        return msg
    }

    fun msgRadioStatus(): MAVLinkMessage {
        val airlinkSignal = handler.aircraft.telemetry.getAirlinkSignal()
        val msg = msg_radio_status()
        // DJI represent the signal quality in percent with range [0, 100], where 100 is the best
        // quality. MAVLink uses [0, 254] as uint8_t

        // AirLink's DownLinkSignalQuality
        msg.rssi = airlinkSignal.downlink?.let { ((it / 100f) * 255f).roundToInt().toShort() }
            ?: Short.MAX_VALUE
        // AirLink's UpLinkSignalQuality
        msg.remrssi = airlinkSignal.uplink?.let { ((it / 100f) * 255f).roundToInt().toShort() }
            ?: Short.MAX_VALUE
        return msg
    }

    fun msgPowerStatus(): MAVLinkMessage = msg_power_status()

    fun msgBatteryStatus(): MAVLinkMessage {
        val battery = BatteryMapper.toMavlink(handler.aircraft.telemetry.getAircraftBattery())
        val msg = msg_battery_status()
        msg.current_consumed = battery.currentConsumed
        msg.temperature = battery.temperature
        msg.voltages = battery.voltages.toIntArray()
        msg.battery_remaining = battery.batteryRemaining
        msg.current_battery = battery.currentBattery
//        client.sendMessage(msg)
        return msg
    }

    fun msgExtendedSys(): MAVLinkMessage {
        val msg = msg_extended_sys_state()
        msg.landed_state = handler.aircraftState.landed.toShort()
        msg.vtol_state = MAV_VTOL_STATE.MAV_VTOL_STATE_MC.toShort()
        return msg
    }

    fun msgMagCal(): MAVLinkMessage {
        val msg = msg_mag_cal_report()
        msg.compass_id = 1
        msg.cal_mask = 1
        msg.cal_status = MAG_CAL_STATUS.MAG_CAL_SUCCESS.toShort()
        msg.old_orientation = MAV_SENSOR_ORIENTATION.MAV_SENSOR_ROTATION_NONE.toShort()
        msg.new_orientation = MAV_SENSOR_ORIENTATION.MAV_SENSOR_ROTATION_NONE.toShort()
        msg.autosaved = 1
        return msg
    }
}
