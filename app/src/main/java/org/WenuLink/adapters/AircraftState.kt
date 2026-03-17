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

    private var state = UnifiedState()

    val mavlink: Int get() = state.mavlink

    val landed: Int get() = state.landed

    val control: ControlAuthority get() = state.controlAuthority

    fun dispatch(event: AircraftState): UnifiedState {
        state = reduce(state, event)
        return state
    }

    fun setControlAuthority(controlAuthority: ControlAuthority): UnifiedState {
        state = state.copy(controlAuthority = controlAuthority)
        return state
    }

    fun isMissionWaypoint() = state.controlAuthority == ControlAuthority.WAYPOINT_MISSION

    fun isTimelineCommand() = state.controlAuthority == ControlAuthority.TIMELINE_COMMAND

    fun isRemoteController() = state.controlAuthority == ControlAuthority.REMOTE_CONTROLLER

    fun isNewControlAuthority(authority: ControlAuthority) = state.controlAuthority != authority

    fun homeSet(isHomeSet: Boolean = true): UnifiedState {
        state = state.copy(isHomeSet = isHomeSet)
        return state
    }

    fun isHomeSet() = state.isHomeSet

    fun isStandBy() = state.mavlink == MAV_STATE.MAV_STATE_STANDBY

    fun isArmed() = state.mavlink == MAV_STATE.MAV_STATE_ACTIVE

    fun isFlying() = state.landed == MAV_LANDED_STATE.MAV_LANDED_STATE_IN_AIR

    fun isOnTheGround() = state.landed == MAV_LANDED_STATE.MAV_LANDED_STATE_ON_GROUND

    private fun reduce(s: UnifiedState, e: AircraftState): UnifiedState = when (e) {
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
