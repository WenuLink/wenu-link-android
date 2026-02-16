package org.WenuLink.adapters

import com.MAVLink.enums.MAV_LANDED_STATE
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
