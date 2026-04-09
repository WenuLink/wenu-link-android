package org.WenuLink.mavlink.params

import com.MAVLink.common.msg_command_int
import com.MAVLink.common.msg_command_long
import com.MAVLink.common.msg_mission_item_int
import org.WenuLink.adapters.MessageUtils

/**
 * Parameter bindings for
 * [MAV_CMD_MISSION_START](https://mavlink.io/en/messages/common.html#MAV_CMD_MISSION_START).
 *
 * @param firstItem first_item: the first mission item to run
 * @param lastItem  last_item: the last mission item to run (after this item is run, the mission
 *                  ends)
 */
data class MissionStartParams(val firstItem: Int, val lastItem: Int) {
    companion object {
        fun from(msg: msg_command_long) = MissionStartParams(
            firstItem = msg.param1.toInt(),
            lastItem = msg.param2.toInt()
        )
    }
}

/**
 * Parameter bindings for
 * [MAV_CMD_DO_REPOSITION](https://mavlink.io/en/messages/common.html#MAV_CMD_DO_REPOSITION ).
 *
 * @param speed     Ground speed, less than 0 (-1) for default
 * @param bitmask   Bitmask of option flags. ([com.MAVLink.enums.MAV_DO_REPOSITION_FLAGS])
 * @param radius    Loiter radius for planes. Positive values only, direction is controlled by Yaw
 *                  value. A value of zero or NaN is ignored.
 * @param yaw       Yaw heading (heading reference defined in Bitmask field). NaN to use the current
 *                  system yaw heading mode (e.g. yaw towards next waypoint, yaw to home, etc.). For
 *                  planes indicates loiter direction (0: clockwise, 1: counter clockwise)
 * @param latitude  Latitude
 * @param longitude Longitude
 * @param altitude  Altitude
 */
data class DoRepositionParams(
    val speed: Float,
    val bitmask: Int,
    val radius: Float,
    val yaw: Float,
    val latitude: Double,
    val longitude: Double,
    val altitude: Float
) {
    companion object {
        fun from(msg: msg_command_int) = DoRepositionParams(
            speed = msg.param1,
            bitmask = msg.param2.toInt(),
            radius = msg.param3,
            yaw = msg.param4,
            latitude = MessageUtils.coordinateMAVLink2DJI(msg.x),
            longitude = MessageUtils.coordinateMAVLink2DJI(msg.y),
            altitude = msg.z
        )
    }
}

/**
 * Parameter bindings for
 * [MAV_CMD_NAV_TAKEOFF](https://mavlink.io/en/messages/common.html#MAV_CMD_NAV_TAKEOFF).
 *
 * @param pitch     Minimum pitch (if airspeed sensor present), desired pitch without sensor
 * @param flags     Bitmask of options flags. (see [NAV_TAKEOFF_FLAGS](https://mavlink.io/en/messages/common.html#NAV_TAKEOFF_FLAGS))
 * @param yaw       Yaw angle (if magnetometer present), ignored without magnetometer. NaN to use
 *                  the current system yaw heading mode (e.g. yaw towards next waypoint, yaw to
 *                  home, etc.).
 * @param latitude  Latitude
 * @param longitude Longitude
 * @param altitude  Altitude
 */
data class NavTakeoffParams(
    val pitch: Float,
    val flags: Int,
    val yaw: Float,
    val latitude: Double,
    val longitude: Double,
    val altitude: Float
) {
    companion object {
        fun from(msg: msg_command_int) = NavTakeoffParams(
            pitch = msg.param1,
            flags = msg.param3.toInt(),
            yaw = msg.param4,
            latitude = MessageUtils.coordinateMAVLink2DJI(msg.x),
            longitude = MessageUtils.coordinateMAVLink2DJI(msg.y),
            altitude = msg.z
        )

        fun from(msg: msg_mission_item_int) = NavTakeoffParams(
            pitch = msg.param1,
            flags = msg.param3.toInt(),
            yaw = msg.param4,
            latitude = MessageUtils.coordinateMAVLink2DJI(msg.x),
            longitude = MessageUtils.coordinateMAVLink2DJI(msg.y),
            altitude = msg.z
        )
    }
}

/**
 * Parameter bindings for
 * [MAV_CMD_NAV_WAYPOINT](https://mavlink.io/en/messages/common.html#MAV_CMD_NAV_WAYPOINT).
 *
 * @param holdTimeSec       Hold time. (ignored by fixed wing, time to stay at waypoint for rotary
 *                          wing)
 * @param acceptanceRadius  Acceptance radius (if the sphere with this radius is hit, the waypoint
 *                          counts as reached)
 * @param passRadius        0 to pass through the WP, if > 0 radius to pass by WP. Positive value
 *                          for clockwise orbit, negative value for counter-clockwise orbit. Allows
 *                          trajectory control.
 * @param yaw               Desired yaw angle at waypoint (rotary wing). NaN to use the current
 *                          system yaw heading mode (e.g. yaw towards next waypoint, yaw to home,
 *                          etc.).
 * @param latitude          Latitude
 * @param longitude         Longitude
 * @param altitude          Altitude
 */
data class NavWaypointParams(
    val holdTimeSec: Float,
    val acceptanceRadius: Float,
    val passRadius: Float,
    val yaw: Float,
    val latitude: Double,
    val longitude: Double,
    val altitude: Float
) {
    companion object {
        fun from(msg: msg_mission_item_int) = NavWaypointParams(
            holdTimeSec = msg.param1,
            acceptanceRadius = msg.param2,
            passRadius = msg.param3,
            yaw = msg.param4,
            latitude = MessageUtils.coordinateMAVLink2DJI(msg.x),
            longitude = MessageUtils.coordinateMAVLink2DJI(msg.y),
            altitude = msg.z
        )
    }
}

/**
 * Parameter bindings for
 * [MAV_CMD_NAV_LAND](https://mavlink.io/en/messages/common.html#MAV_CMD_NAV_LAND).
 *
 * @param abortAlt  Minimum target altitude if landing is aborted (0 = undefined/use system
 *                  default).
 * @param landMode  Precision land mode. ([com.MAVLink.enums.PRECISION_LAND_MODE])
 * @param yawAngle  Desired yaw angle. NaN to use the current system yaw heading mode (e.g. yaw
 *                  towards next waypoint, yaw to home, etc.).
 * @param latitude  Latitude.
 * @param longitude Longitude.
 * @param altitude  Landing altitude (ground level in current frame).
 */
data class NavLandParams(
    val abortAlt: Float,
    val landMode: Int,
    val yawAngle: Float,
    val latitude: Double,
    val longitude: Double,
    val altitude: Float
) {
    companion object {
        fun from(msg: msg_command_int) = NavLandParams(
            abortAlt = msg.param1,
            landMode = msg.param2.toInt(),
            yawAngle = msg.param4,
            latitude = MessageUtils.coordinateMAVLink2DJI(msg.x),
            longitude = MessageUtils.coordinateMAVLink2DJI(msg.y),
            altitude = msg.z
        )
    }
}

/**
 * Parameter bindings for
 * [MAV_CMD_NAV_DELAY](https://mavlink.io/en/messages/common.html#MAV_CMD_NAV_DELAY).
 *
 * @param delaySec  Delay (-1 to enable time-of-day fields)
 * @param hours     hour (24h format, UTC, -1 to ignore)
 * @param minutes   minute (24h format, UTC, -1 to ignore)
 * @param seconds   second (24h format, UTC, -1 to ignore)
 */
data class NavDelayParams(val delaySec: Int, val hours: Int, val minutes: Int, val seconds: Int) {
    companion object {
        fun from(msg: msg_command_long) = NavDelayParams(
            delaySec = msg.param1.toInt(),
            hours = msg.param2.toInt(),
            minutes = msg.param3.toInt(),
            seconds = msg.param4.toInt()
        )

        fun from(msg: msg_mission_item_int) = NavDelayParams(
            delaySec = msg.param1.toInt(),
            hours = msg.param2.toInt(),
            minutes = msg.param3.toInt(),
            seconds = msg.param4.toInt()
        )
    }
}

/**
 * Parameter bindings for
 * [MAV_CMD_CONDITION_YAW](https://mavlink.io/en/messages/common.html#MAV_CMD_CONDITION_YAW).
 *
 * @param angleDeg          target angle [0-360]. Absolute angles: 0 is north. Relative angle: 0 is
 *                          initial yaw. Direction set by param3.
 * @param angularSpeedDegS  angular speed
 * @param clockwise         direction: -1: counter clockwise, 0: shortest direction, 1: clockwise
 * @param relative          Relative offset (MAV_BOOL_FALSE: absolute angle). Values not equal to 0
 *                          or 1 are invalid.
 */
data class ConditionYawParams(
    val angleDeg: Float,
    val angularSpeedDegS: Float,
    val clockwise: Int,
    val relative: Boolean?
) {
    companion object {
        fun from(msg: msg_command_long) = ConditionYawParams(
            angleDeg = msg.param1,
            angularSpeedDegS = msg.param2,
            clockwise = msg.param3.toInt(),
            relative = ParamUtils.toBoolean(msg.param4)
        )

        fun from(msg: msg_mission_item_int) = ConditionYawParams(
            angleDeg = msg.param1,
            angularSpeedDegS = msg.param2,
            clockwise = msg.param3.toInt(),
            relative = ParamUtils.toBoolean(msg.param4)
        )
    }
}
