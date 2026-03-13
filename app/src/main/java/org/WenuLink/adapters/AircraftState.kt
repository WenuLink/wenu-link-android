package org.WenuLink.adapters

import com.MAVLink.enums.MAV_LANDED_STATE
import com.MAVLink.enums.MAV_MODE_FLAG
import com.MAVLink.enums.MAV_STATE

enum class ControlAuthority {
    NONE,
    WAYPOINT_MISSION,
    TIMELINE_COMMAND,
    REMOTE_CONTROLLER
}

sealed interface AircraftState {
    object Boot : AircraftState
    object Calibration : AircraftState
    object Standby : AircraftState
    object Arm : AircraftState
    object Takeoff : AircraftState
    object InAir : AircraftState
    object Land : AircraftState
    object OnGround : AircraftState
    object FlightTermination : AircraftState
    object PowerOff : AircraftState
}

/**
 * Single source of truth for MAVLink + landed state transitions.
 * FSM / Reducer pattern.
 */
class AircraftStateMachine {

    data class UnifiedState(
        val mavlink: Int = MAV_STATE.MAV_STATE_UNINIT,
        val landed: Int = MAV_LANDED_STATE.MAV_LANDED_STATE_UNDEFINED,
        val isHomeSet: Boolean = false,
        val controlAuthority: ControlAuthority = ControlAuthority.NONE
    )

    private var _state = UnifiedState()

    val mavlink: Int get() = _state.mavlink

    val landed: Int get() = _state.landed

    val control: ControlAuthority get() = _state.controlAuthority

    fun dispatch(event: AircraftState): UnifiedState {
        _state = reduce(_state, event)
        return _state
    }

    fun setControlAuthority(controlAuthority: ControlAuthority): UnifiedState {
        _state = _state.copy(controlAuthority = controlAuthority)
        return _state
    }

    fun isMissionWaypoint() = _state.controlAuthority == ControlAuthority.WAYPOINT_MISSION

    fun isTimelineCommand() = _state.controlAuthority == ControlAuthority.TIMELINE_COMMAND

    fun isRemoteController() = _state.controlAuthority == ControlAuthority.REMOTE_CONTROLLER

    fun isNewControlAuthority(authority: ControlAuthority) =
        _state.controlAuthority != authority

    fun homeSet(isHomeSet: Boolean = true): UnifiedState {
        _state = _state.copy(isHomeSet = isHomeSet)
        return _state
    }

    fun isHomeSet() = _state.isHomeSet

    fun isStandBy() = _state.mavlink == MAV_STATE.MAV_STATE_STANDBY

    fun isArmed() = _state.mavlink == MAV_STATE.MAV_STATE_ACTIVE

    fun isFlying() = _state.landed == MAV_LANDED_STATE.MAV_LANDED_STATE_IN_AIR

    fun isOnTheGround() = _state.landed == MAV_LANDED_STATE.MAV_LANDED_STATE_ON_GROUND

    private fun reduce(s: UnifiedState, e: AircraftState): UnifiedState =
        when (e) {
            AircraftState.Boot ->
                s.copy(mavlink = MAV_STATE.MAV_STATE_BOOT)

            AircraftState.Calibration ->
                s.copy(mavlink = MAV_STATE.MAV_STATE_CALIBRATING)

            AircraftState.Standby ->
                s.copy(
                    mavlink = MAV_STATE.MAV_STATE_STANDBY,
                    landed = MAV_LANDED_STATE.MAV_LANDED_STATE_ON_GROUND
                )

            AircraftState.Arm ->
                s.copy(mavlink = MAV_STATE.MAV_STATE_ACTIVE)

            AircraftState.Takeoff ->
                s.copy(landed = MAV_LANDED_STATE.MAV_LANDED_STATE_TAKEOFF)

            AircraftState.InAir ->
                s.copy(landed = MAV_LANDED_STATE.MAV_LANDED_STATE_IN_AIR)

            AircraftState.Land ->
                s.copy(
                    mavlink = MAV_STATE.MAV_STATE_FLIGHT_TERMINATION,
                    landed = MAV_LANDED_STATE.MAV_LANDED_STATE_LANDING
                )

            AircraftState.OnGround ->
                s.copy(landed = MAV_LANDED_STATE.MAV_LANDED_STATE_ON_GROUND)

            AircraftState.FlightTermination ->
                s.copy(mavlink = MAV_STATE.MAV_STATE_FLIGHT_TERMINATION)

            AircraftState.PowerOff ->
                s.copy(mavlink = MAV_STATE.MAV_STATE_POWEROFF)
        }
}

/**
 * Internal strategy for MAVLink base mode flag composition.
 */
private enum class BaseModeType(val flags: Int) {

    MANUAL(
        MAV_MODE_FLAG.MAV_MODE_FLAG_CUSTOM_MODE_ENABLED or
                MAV_MODE_FLAG.MAV_MODE_FLAG_STABILIZE_ENABLED or
                MAV_MODE_FLAG.MAV_MODE_FLAG_MANUAL_INPUT_ENABLED
    ),

    GUIDED(
        MAV_MODE_FLAG.MAV_MODE_FLAG_CUSTOM_MODE_ENABLED or
                MAV_MODE_FLAG.MAV_MODE_FLAG_STABILIZE_ENABLED or
                MAV_MODE_FLAG.MAV_MODE_FLAG_GUIDED_ENABLED
    ),

    AUTO(
        MAV_MODE_FLAG.MAV_MODE_FLAG_CUSTOM_MODE_ENABLED or
                MAV_MODE_FLAG.MAV_MODE_FLAG_STABILIZE_ENABLED or
                MAV_MODE_FLAG.MAV_MODE_FLAG_AUTO_ENABLED
    );
}

enum class ArduCopterFlightMode(
    val mode: Long,
    private val baseModeType: BaseModeType
) {
    // https://ardupilot.org/dev/docs/apmcopter-adding-a-new-flight-mode.html
    STABILIZE(0, BaseModeType.MANUAL),      // manual airframe angle with manual throttle
    ACRO(1, BaseModeType.MANUAL),           // manual body-frame angular rate with manual throttle
    ALT_HOLD(2, BaseModeType.MANUAL),       // manual airframe angle with automatic throttle
    AUTO(3, BaseModeType.AUTO),             // fully automatic waypoint control using mission commands
    GUIDED(4, BaseModeType.GUIDED),         // fully automatic fly to coordinate or fly at velocity/direction using GCS immediate commands
    LOITER(5, BaseModeType.MANUAL),         // automatic horizontal acceleration with automatic throttle
    RTL(6, BaseModeType.AUTO),              // automatic return to launching point
    CIRCLE(7, BaseModeType.MANUAL),         // automatic circular flight with automatic throttle
    LAND(9, BaseModeType.AUTO),             // automatic landing with horizontal position control
    DRIFT(11, BaseModeType.MANUAL),         // semi-autonomous position, yaw and throttle control
    SPORT(13, BaseModeType.MANUAL),         // manual earth-frame angular rate control with manual throttle
    FLIP(14, BaseModeType.MANUAL),          // automatically flip the vehicle on the roll axis
    AUTOTUNE(15, BaseModeType.MANUAL),      // automatically tune the vehicle's roll and pitch gains
    POSHOLD(16, BaseModeType.MANUAL),       // automatic position hold with manual override, with automatic throttle
    BRAKE(17, BaseModeType.AUTO),           // full-brake using inertial/GPS system, no pilot input
    THROW(18, BaseModeType.AUTO),           // throw to launch mode using inertial/GPS system, no pilot input
    AVOID_ADSB(19, BaseModeType.AUTO),      // automatic avoidance of obstacles in the macro scale - e.g. full-sized aircraft
    GUIDED_NOGPS(20, BaseModeType.GUIDED),  // guided mode but only accepts attitude and altitude
    SMART_RTL(21, BaseModeType.AUTO),       // SMART_RTL returns to home by retracing its steps
    FLOWHOLD(22, BaseModeType.MANUAL),      // FLOWHOLD holds position with optical flow without rangefinder
    FOLLOW(23, BaseModeType.AUTO),          // follow attempts to follow another vehicle or ground station
    ZIGZAG(24, BaseModeType.AUTO),          // ZIGZAG mode is able to fly in a zigzag manner with predefined point A and point B
    SYSTEMID(25, BaseModeType.AUTO),        // System ID mode produces automated system identification signals in the controllers
    AUTOROTATE(26, BaseModeType.AUTO);      // Autonomous autorotation
    // NEWMODE(27, BaseModeType)

    val baseMode: Int
        get() = baseModeType.flags

    companion object {
        private val byValue = entries.associateBy { it.mode }

        fun from(value: Long): ArduCopterFlightMode? =
            byValue[value]
    }
}
