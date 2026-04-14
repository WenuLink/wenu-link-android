package org.WenuLink.mavlink.messages

import com.MAVLink.common.msg_command_int
import com.MAVLink.common.msg_command_long
import com.MAVLink.common.msg_mission_item_int
import com.MAVLink.enums.MAV_CMD
import com.MAVLink.enums.MAV_FRAME
import com.MAVLink.enums.MAV_MISSION_TYPE
import org.WenuLink.adapters.MessageUtils

/**
 * Message bindings for
 * [MAV_CMD_MISSION_START](https://mavlink.io/en/messages/common.html#MAV_CMD_MISSION_START).
 *
 * @param firstItem first_item: the first mission item to run
 * @param lastItem  last_item: the last mission item to run (after this item is run, the mission
 *                  ends)
 */
data class MissionStartCommandLong(val firstItem: Int, val lastItem: Int) {
    constructor(msg: msg_command_long) : this(
        firstItem = msg.param1.toInt(),
        lastItem = msg.param2.toInt()
    )
}

/**
 * Message bindings for
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
data class DoRepositionCommandInt(
    val speed: Float,
    val bitmask: Int,
    val radius: Float,
    val yaw: Float,
    val latitude: Double,
    val longitude: Double,
    val altitude: Float
) {
    constructor(msg: msg_command_int) : this(
        speed = msg.param1,
        bitmask = msg.param2.toInt(),
        radius = msg.param3,
        yaw = msg.param4,
        latitude = MessageUtils.coordinateMAVLink2DJI(msg.x),
        longitude = MessageUtils.coordinateMAVLink2DJI(msg.y),
        altitude = msg.z
    )
}

/**
 * Message bindings for
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
data class NavTakeoffMissionItem(
    val pitch: Float = 0f,
    val flags: Int = 0,
    val yaw: Float = Float.NaN,
    val latitude: Double,
    val longitude: Double,
    val altitude: Float
) {
    constructor(msg: msg_mission_item_int) : this(
        pitch = msg.param1,
        flags = msg.param3.toInt(),
        yaw = msg.param4,
        latitude = MessageUtils.coordinateMAVLink2DJI(msg.x),
        longitude = MessageUtils.coordinateMAVLink2DJI(msg.y),
        altitude = msg.z
    )

    fun toMavLink(seq: Int): msg_mission_item_int = msg_mission_item_int().apply {
        this.seq = seq
        frame = MAV_FRAME.MAV_FRAME_GLOBAL_RELATIVE_ALT.toShort()
        command = MAV_CMD.MAV_CMD_NAV_TAKEOFF
        mission_type = MAV_MISSION_TYPE.MAV_MISSION_TYPE_MISSION.toShort()
        param1 = pitch
        param3 = flags.toFloat()
        param4 = yaw
        x = MessageUtils.coordinateDJI2MAVLink(latitude)
        y = MessageUtils.coordinateDJI2MAVLink(longitude)
        z = altitude
    }
}

/**
 * Message bindings for
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
data class NavWaypointMissionItem(
    val holdTimeSec: Float = 0f,
    val acceptanceRadius: Float = 0f,
    val passRadius: Float = 0f,
    val yaw: Float = Float.NaN,
    val latitude: Double,
    val longitude: Double,
    val altitude: Float
) {
    constructor(msg: msg_mission_item_int) : this(
        holdTimeSec = msg.param1,
        acceptanceRadius = msg.param2,
        passRadius = msg.param3,
        yaw = msg.param4,
        latitude = MessageUtils.coordinateMAVLink2DJI(msg.x),
        longitude = MessageUtils.coordinateMAVLink2DJI(msg.y),
        altitude = msg.z
    )

    fun toMavLink(seq: Int): msg_mission_item_int = msg_mission_item_int().apply {
        this.seq = seq
        frame = MAV_FRAME.MAV_FRAME_GLOBAL_RELATIVE_ALT.toShort()
        command = MAV_CMD.MAV_CMD_NAV_WAYPOINT
        mission_type = MAV_MISSION_TYPE.MAV_MISSION_TYPE_MISSION.toShort()
        param1 = holdTimeSec
        param2 = acceptanceRadius
        param3 = passRadius
        param4 = yaw
        x = MessageUtils.coordinateDJI2MAVLink(latitude)
        y = MessageUtils.coordinateDJI2MAVLink(longitude)
        z = altitude
    }
}

/**
 * Message bindings for
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
data class NavLandMissionItem(
    val abortAlt: Float = 0f,
    val landMode: Int = 0,
    val yawAngle: Float = Float.NaN,
    val latitude: Double,
    val longitude: Double,
    val altitude: Float
) {
    fun toMavLink(seq: Int): msg_mission_item_int = msg_mission_item_int().apply {
        this.seq = seq
        frame = MAV_FRAME.MAV_FRAME_GLOBAL_RELATIVE_ALT.toShort()
        command = MAV_CMD.MAV_CMD_NAV_LAND
        mission_type = MAV_MISSION_TYPE.MAV_MISSION_TYPE_MISSION.toShort()
        param1 = abortAlt
        param2 = landMode.toFloat()
        param4 = yawAngle
        x = MessageUtils.coordinateDJI2MAVLink(latitude)
        y = MessageUtils.coordinateDJI2MAVLink(longitude)
        z = altitude
    }
}

/**
 * Message bindings for
 * [MAV_CMD_NAV_DELAY](https://mavlink.io/en/messages/common.html#MAV_CMD_NAV_DELAY).
 *
 * @param delaySec  Delay (-1 to enable time-of-day fields)
 * @param hours     hour (24h format, UTC, -1 to ignore)
 * @param minutes   minute (24h format, UTC, -1 to ignore)
 * @param seconds   second (24h format, UTC, -1 to ignore)
 */
data class NavDelayMessage(val delaySec: Int, val hours: Int, val minutes: Int, val seconds: Int) {
    constructor(msg: msg_command_long) : this(
        delaySec = msg.param1.toInt(),
        hours = msg.param2.toInt(),
        minutes = msg.param3.toInt(),
        seconds = msg.param4.toInt()
    )

    constructor(msg: msg_mission_item_int) : this(
        delaySec = msg.param1.toInt(),
        hours = msg.param2.toInt(),
        minutes = msg.param3.toInt(),
        seconds = msg.param4.toInt()
    )
}

/**
 * Message bindings for
 * [MAV_CMD_CONDITION_YAW](https://mavlink.io/en/messages/common.html#MAV_CMD_CONDITION_YAW).
 *
 * @param angleDeg          target angle [0-360]. Absolute angles: 0 is north. Relative angle: 0 is
 *                          initial yaw. Direction set by param3.
 * @param angularSpeedDegS  angular speed
 * @param clockwise         direction: -1: counter clockwise, 0: shortest direction, 1: clockwise
 * @param relative          Relative offset (MAV_BOOL_FALSE: absolute angle). Values not equal to 0
 *                          or 1 are invalid.
 */
data class ConditionYawMessage(
    val angleDeg: Float,
    val angularSpeedDegS: Float,
    val clockwise: Int,
    val relative: Boolean?
) {
    constructor(msg: msg_command_long) : this(
        angleDeg = msg.param1,
        angularSpeedDegS = msg.param2,
        clockwise = msg.param3.toInt(),
        relative = ParamUtils.toBoolean(msg.param4)
    )

    constructor(msg: msg_mission_item_int) : this(
        angleDeg = msg.param1,
        angularSpeedDegS = msg.param2,
        clockwise = msg.param3.toInt(),
        relative = ParamUtils.toBoolean(msg.param4)
    )
}
