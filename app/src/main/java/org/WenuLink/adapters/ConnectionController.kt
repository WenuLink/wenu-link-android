package org.WenuLink.adapters

import android.util.Log
import com.MAVLink.Messages.MAVLinkMessage
import com.MAVLink.common.msg_altitude
import com.MAVLink.common.msg_attitude
import com.MAVLink.common.msg_battery_status
import com.MAVLink.common.msg_global_position_int
import com.MAVLink.common.msg_gps_raw_int
import com.MAVLink.common.msg_home_position
import com.MAVLink.common.msg_power_status
import com.MAVLink.common.msg_radio_status
import com.MAVLink.common.msg_sys_status
import com.MAVLink.common.msg_system_time
import com.MAVLink.common.msg_timesync
import com.MAVLink.common.msg_timesync.MAVLINK_MSG_ID_TIMESYNC
import com.MAVLink.common.msg_vfr_hud
import com.MAVLink.common.msg_vibration
import com.MAVLink.enums.GPS_FIX_TYPE
import com.MAVLink.enums.MAV_AUTOPILOT
import com.MAVLink.enums.MAV_MODE_FLAG
import com.MAVLink.enums.MAV_STATE
import com.MAVLink.enums.MAV_TYPE
import com.MAVLink.minimal.msg_heartbeat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.WenuLink.mavlink.MAVLinkClient
import org.WenuLink.mavlink.MAVLinkController
import kotlin.experimental.or
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * MAVLinkController class to deal with the heartbeat/connection service and related MAVLink messages.
 *
 * https://mavlink.io/en/services/heartbeat.html
 */
class ConnectionController (
    private var client: MAVLinkClient,
    private var connectionScope: CoroutineScope
) : MAVLinkController {
    private val TAG: String = ConnectionController::class.java.simpleName
    private val telemetry: TelemetryHandler = TelemetryHandler.getInstance()
    private var ticks: Long = 0

    init {
        telemetry.registerListenerScope(connectionScope)
    }

    fun startTelemetry(onResult: (String?) -> Unit) {
        ticks = 0
        connectionScope.launch {
            telemetry.start(true, connectionScope, onResult)
        }
    }

    fun stopTelemetry(onResult: (String?) -> Unit) {
        connectionScope.launch {
            telemetry.start(false, connectionScope, onResult)
        }
    }

    fun isTelemetryRunning(): Boolean = telemetry.isActive()

    // https://ardupilot.org/copter/docs/ArduCopter_MAVLink_Messages.html#outgoing-messages
    fun tick(timeMillis: Long) {
        if (!client.isReady()) {
            Log.e(TAG, "MAVLink client is not ready yet!.")
            return
        }

        val telemetryData = telemetry.getTelemetryData() ?: run {
            Log.w(TAG, "No telemetry data yet!")
            return
        }

        ticks += timeMillis
        if (ticks % 100 == 0L) {
            sendAttitude(telemetryData)
            sendAltitude(telemetryData)
            sendVibration()
            val rcData = telemetry.getRCData()
            if (rcData != null)
                sendHUD(telemetryData, rcData)
        }
        if (ticks % 200 == 0L) {
            sendGlobalPositionInt(telemetryData)
        }
        if (ticks % 300 == 0L) {
            sendRawGPSInt(telemetryData)
            sendRadioStatus()
//            checkRCChannels()  // prevent execution in case of RC input received
        }
        if (ticks % 1000 == 0L) {
            sendHeartbeat(telemetryData)
            sendSysStatus(telemetry.getAircraftBattery())
            sendPowerStatus()
            sendBatteryStatus(telemetry.getAircraftBattery())
//            checkLanding()  // DJI landing callback
        }
        if (ticks % 5000 == 0L) {
            val homePos = telemetry.getHomePosition()
            if (homePos != null)
                sendHomePosition(
                    homePos.first,
                    homePos.second,
                    homePos.third
                )
        }
    }

    override fun processMessage(msg: MAVLinkMessage) {
        when (msg.msgid) {
            MAVLINK_MSG_ID_TIMESYNC -> sendTimeSync(msg)
        }
    }

    fun sendTimeSync(msg: MAVLinkMessage) {
        val outMsg = msg_timesync()
        outMsg.tc1 = System.nanoTime()
        outMsg.ts1 = (msg as msg_timesync).ts1
        outMsg.target_system = msg.sysid.toShort()
        outMsg.target_component = msg.compid.toShort()
        client.sendMessage(outMsg)
    }

    fun sendSystemTime(startTimestamp: Long) {
        val msg = msg_system_time()
        msg.time_unix_usec = System.nanoTime()
        msg.time_boot_ms = System.currentTimeMillis() - startTimestamp
        client.sendMessage(msg)
    }

    fun sendHeartbeat(telemetry: TelemetryData) {
        val heartbeat = msg_heartbeat()
        heartbeat.type = MAV_TYPE.MAV_TYPE_QUADROTOR.toShort()
        heartbeat.autopilot = MAV_AUTOPILOT.MAV_AUTOPILOT_GENERIC.toShort()
        heartbeat.system_status = MAV_STATE.MAV_STATE_ACTIVE.toShort()
        heartbeat.mavlink_version = 3
        // mode definition
        // For base mode logic, see Copter::sendHeartBeat() in ArduCopter/GCS_Mavlink.cpp
        var baseMode: Short = MAV_MODE_FLAG.MAV_MODE_FLAG_CUSTOM_MODE_ENABLED.toShort()
        baseMode = baseMode.or(MAV_MODE_FLAG.MAV_MODE_FLAG_MANUAL_INPUT_ENABLED.toShort())

        if (telemetry.flightGuided) baseMode = baseMode.or(MAV_MODE_FLAG.MAV_MODE_FLAG_GUIDED_ENABLED.toShort())

        heartbeat.base_mode = baseMode
        heartbeat.custom_mode = telemetry.flightMode.mode
        client.sendMessage(heartbeat)
    }

    fun sendAttitude(telemetry: TelemetryData) {
        val msg = msg_attitude()
        // TODO: this next line causes an exception
        // msg.time_boot_ms = getTimestampMilliseconds();
        msg.roll = (telemetry.roll * Math.PI / 180).toFloat()
        msg.pitch = (telemetry.pitch * Math.PI / 180).toFloat()
        msg.yaw = (telemetry.yaw * Math.PI / 180).toFloat()
        // TODO msg.rollspeed = 0;
        // TODO msg.pitchspeed = 0;
        // TODO msg.yawspeed = 0;
        client.sendMessage(msg)
    }

    fun sendAltitude(telemetry: TelemetryData) {
        val msg = msg_altitude()
        msg.altitude_relative = telemetry.altitude
        client.sendMessage(msg)
    }

    fun sendVibration() {
        client.sendMessage(msg_vibration())
    }

    fun sendHUD(telemetry: TelemetryData, rcData: RCData) {
        val msg = msg_vfr_hud()
        // Mavlink: Current airspeed in m/s
        // DJI: unclear whether getState() returns airspeed or groundspeed
        msg.airspeed = sqrt(
            telemetry.velocityX.toDouble().pow(2.0)
                    + telemetry.velocityY.toDouble().pow(2.0)
        ).toFloat()
        // Mavlink: Current ground speed in m/s. For now, just echoing airspeed.
        msg.groundspeed = msg.airspeed
        // yaw angle
        var heading = telemetry.yaw
        if (heading < 0) heading += 360
        msg.heading = heading.toInt().toShort()
        // vertical info
        msg.throttle = rcData.throttleSetting
        msg.alt = telemetry.altitude
        // Mavlink: Current climb rate in meters/second
        // DJI: m/s, positive values down
        msg.climb = -telemetry.velocityZ
        client.sendMessage(msg)
    }

    fun sendGlobalPositionInt(telemetry: TelemetryData) {
        val msg = msg_global_position_int()
        msg.lat = (telemetry.latitude * 10_000_000).toInt()
        msg.lon = (telemetry.longitude * 10_000_000).toInt()
        msg.alt = (telemetry.altitude * 1_000).toInt()
        // NOTE: Commented out this field, because msg.relative_alt seems to be intended for altitude above the current terrain,
        // but DJI reports altitude above home point.
        // Mavlink: Millimeters above ground (unspecified: presumably above home point?)
        // DJI: relative altitude of the aircraft relative to take off location, measured by barometer, in meters.
        msg.relative_alt = (telemetry.altitude * 1_000).toInt()
        msg.vx = (telemetry.velocityX * 100).toInt().toShort()
        msg.vy = (telemetry.velocityY * 100).toInt().toShort()
        msg.vz = (telemetry.velocityZ * 100).toInt().toShort()
        var yaw = telemetry.yaw
        if (yaw < 0) yaw += 360
        msg.hdg = (yaw * 100).toInt()
        client.sendMessage(msg)
    }

    fun sendRawGPSInt(telemetry: TelemetryData) {
        val msg = msg_gps_raw_int()
        msg.time_usec = System.currentTimeMillis() / 10
        msg.lat = (telemetry.latitude * 10_000_000).toInt()
        msg.lon = (telemetry.longitude * 10_000_000).toInt()
        msg.satellites_visible = telemetry.satelliteCount.toShort()
        // DJI reports signal quality on a scale of 1-11
        // Mavlink has separate codes for fix type.
        if (telemetry.gpsLevel[0] || telemetry.gpsLevel[1]) {
            msg.fix_type = GPS_FIX_TYPE.GPS_FIX_TYPE_NO_FIX.toShort()
        }
        else if (telemetry.gpsLevel[2]) {
            msg.fix_type = GPS_FIX_TYPE.GPS_FIX_TYPE_2D_FIX.toShort()
        }
        else if (telemetry.gpsLevel[3] || telemetry.gpsLevel[4] || telemetry.gpsLevel[5]) {
            msg.fix_type = GPS_FIX_TYPE.GPS_FIX_TYPE_3D_FIX.toShort()
        }
        else {
            msg.fix_type = GPS_FIX_TYPE.GPS_FIX_TYPE_NO_FIX.toShort()
        }
        client.sendMessage(msg)
    }

    fun sendRadioStatus() {
        val msg = msg_radio_status()
        // DJI represent the signal quality in percent with range [0, 100], where 100 is the best quality.
        // MAVLink uses [0, 254] as uint8_t
        msg.rssi = 0 // TODO: work out units conversion maybe from AirLink's DownLinkSignalQuality
        msg.remrssi = 0 // TODO: work out units conversion from AirLink's UpLinkSignalQuality
        client.sendMessage(msg)
    }

    fun sendSysStatus(aircraftBattery: BatteryData) {
        val msg = msg_sys_status()
        msg.battery_remaining = aircraftBattery.percentCharge.toByte()
        msg.voltage_battery = aircraftBattery.voltage
        msg.current_battery = aircraftBattery.current.toShort()
        client.sendMessage(msg)
    }

    fun sendPowerStatus() {
        client.sendMessage(msg_power_status())
    }

    fun sendBatteryStatus(aircraftBattery: BatteryData) {
        val msg = msg_battery_status()
        msg.current_consumed = aircraftBattery.fullChargeCapacity - aircraftBattery.chargeRemaining
        msg.voltages = aircraftBattery.voltageCells
        msg.temperature = (aircraftBattery.temperature * 100.0).toInt().toShort()
        msg.current_battery = (aircraftBattery.current * 10).toShort()
        msg.battery_remaining = (aircraftBattery.chargeRemaining.toFloat() / aircraftBattery.fullChargeCapacity.toFloat() * 100.0F).toInt().toByte()
        client.sendMessage(msg)
    }

    fun sendHomePosition(latitude: Double, longitude: Double, height: Int) {
        Log.i(TAG, "sendHomePosition()")
        val msg = msg_home_position()
        msg.latitude = (latitude * 10_000_000).toInt()
        msg.longitude = (longitude * 10_000_000).toInt()
        msg.altitude = height
        client.sendMessage(msg)
    }
}