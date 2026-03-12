package org.WenuLink.adapters

import com.MAVLink.enums.MAV_MODE_FLAG

enum class ArduCopterFlightMode(val mode: Long) {
    // https://ardupilot.org/dev/docs/apmcopter-adding-a-new-flight-mode.html
    STABILIZE(0), // manual airframe angle with manual throttle
    ACRO(1), // manual body-frame angular rate with manual throttle
    ALT_HOLD(2), // manual airframe angle with automatic throttle
    AUTO(3), // fully automatic waypoint control using mission commands

    // fully automatic fly to coordinate or fly at velocity/direction using GCS immediate commands
    GUIDED(4),
    LOITER(5), // automatic horizontal acceleration with automatic throttle
    RTL(6), // automatic return to launching point
    CIRCLE(7), // automatic circular flight with automatic throttle
    LAND(9), // automatic landing with horizontal position control
    DRIFT(11), // semi-autonomous position, yaw and throttle control
    SPORT(13), // manual earth-frame angular rate control with manual throttle
    FLIP(14), // automatically flip the vehicle on the roll axis
    AUTOTUNE(15), // automatically tune the vehicle's roll and pitch gains
    POSHOLD(16), // automatic position hold with manual override, with automatic throttle
    BRAKE(17), // full-brake using inertial/GPS system, no pilot input
    THROW(18), // throw to launch mode using inertial/GPS system, no pilot input

    // automatic avoidance of obstacles in the macro scale - e.g. full-sized aircraft
    AVOID_ADSB(19),
    GUIDED_NOGPS(20), // guided mode but only accepts attitude and altitude
    SMART_RTL(21), // SMART_RTL returns to home by retracing its steps
    FLOWHOLD(22), // FLOWHOLD holds position with optical flow without rangefinder
    FOLLOW(23), // follow attempts to follow another vehicle or ground station

    // ZIGZAG mode is able to fly in a zigzag manner with predefined point A and point B
    ZIGZAG(24),

    // System ID mode produces automated system identification signals in the controllers
    SYSTEMID(25),
    AUTOROTATE(26); // Autonomous autorotation

    // NEW_MODE(27)        // your new flight mode
    companion object {
        private val byValue = entries.associateBy { it.mode }

        fun from(value: Long): ArduCopterFlightMode? = byValue[value]
    }
}

private fun flags(vararg f: Int) = f.reduce { a, b -> a or b }

private val MANUAL_FLAGS = flags(
    MAV_MODE_FLAG.MAV_MODE_FLAG_CUSTOM_MODE_ENABLED,
    MAV_MODE_FLAG.MAV_MODE_FLAG_STABILIZE_ENABLED,
    MAV_MODE_FLAG.MAV_MODE_FLAG_MANUAL_INPUT_ENABLED
)
private val GUIDED_FLAGS = flags(
    MAV_MODE_FLAG.MAV_MODE_FLAG_CUSTOM_MODE_ENABLED,
    MAV_MODE_FLAG.MAV_MODE_FLAG_STABILIZE_ENABLED,
    MAV_MODE_FLAG.MAV_MODE_FLAG_GUIDED_ENABLED
)
private val AUTO_FLAGS = flags(
    MAV_MODE_FLAG.MAV_MODE_FLAG_CUSTOM_MODE_ENABLED,
    MAV_MODE_FLAG.MAV_MODE_FLAG_STABILIZE_ENABLED,
    MAV_MODE_FLAG.MAV_MODE_FLAG_AUTO_ENABLED
)

val flightMode2baseMode = mapOf(
    ArduCopterFlightMode.STABILIZE to MANUAL_FLAGS,
    ArduCopterFlightMode.ALT_HOLD to MANUAL_FLAGS,
    ArduCopterFlightMode.LOITER to MANUAL_FLAGS,
    ArduCopterFlightMode.GUIDED to GUIDED_FLAGS,
    ArduCopterFlightMode.GUIDED_NOGPS to GUIDED_FLAGS,
    ArduCopterFlightMode.AUTO to AUTO_FLAGS,
    ArduCopterFlightMode.RTL to AUTO_FLAGS,
    ArduCopterFlightMode.LAND to AUTO_FLAGS,
    ArduCopterFlightMode.SMART_RTL to AUTO_FLAGS
)
