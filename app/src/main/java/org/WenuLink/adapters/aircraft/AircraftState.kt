package org.WenuLink.adapters.aircraft

import com.MAVLink.enums.MAV_LANDED_STATE
import com.MAVLink.enums.MAV_MODE_FLAG
import com.MAVLink.enums.MAV_STATE

enum class ControlAuthority {
    NONE,
    WAYPOINT_MISSION,
    TIMELINE_COMMAND,
    REMOTE_CONTROLLER
}

data class AircraftState(
    val mavlink: Int = MAV_STATE.MAV_STATE_UNINIT,
    val landed: Int = MAV_LANDED_STATE.MAV_LANDED_STATE_UNDEFINED,
    val controlAuthority: ControlAuthority = ControlAuthority.NONE,
    val homeCoordinates: Coordinates3D? = null
) {

    fun isHomeSet() = homeCoordinates != null

    fun isStandBy() = mavlink == MAV_STATE.MAV_STATE_STANDBY

    fun isArmed() = mavlink == MAV_STATE.MAV_STATE_ACTIVE

    fun isFlying() = landed == MAV_LANDED_STATE.MAV_LANDED_STATE_IN_AIR

    fun isLanding() = landed == MAV_LANDED_STATE.MAV_LANDED_STATE_LANDING

    fun isOnTheGround() = landed == MAV_LANDED_STATE.MAV_LANDED_STATE_ON_GROUND

    fun isUninitialized() = mavlink == MAV_STATE.MAV_STATE_UNINIT

    fun isBoot() = mavlink == MAV_STATE.MAV_STATE_BOOT

    fun isPowerOff() = mavlink == MAV_STATE.MAV_STATE_POWEROFF

    fun resolveFrom(isArmed: Boolean, isFlying: Boolean): AircraftState {
        val mavState = when (this.mavlink) {
            MAV_STATE.MAV_STATE_FLIGHT_TERMINATION,
            MAV_STATE.MAV_STATE_CRITICAL,
            MAV_STATE.MAV_STATE_EMERGENCY -> this.mavlink

            else -> if (isArmed) {
                MAV_STATE.MAV_STATE_ACTIVE
            } else {
                MAV_STATE.MAV_STATE_STANDBY
            }
        }

        val landedState = when {
            !isFlying && this.landed == MAV_LANDED_STATE.MAV_LANDED_STATE_LANDING ->
                MAV_LANDED_STATE.MAV_LANDED_STATE_ON_GROUND

            isFlying && this.landed == MAV_LANDED_STATE.MAV_LANDED_STATE_ON_GROUND ->
                MAV_LANDED_STATE.MAV_LANDED_STATE_TAKEOFF

            isFlying -> MAV_LANDED_STATE.MAV_LANDED_STATE_IN_AIR

            else -> MAV_LANDED_STATE.MAV_LANDED_STATE_ON_GROUND
        }

        return this.copy(
            mavlink = mavState,
            landed = landedState
        )
    }
}

sealed interface StateTransition {

    fun canTransition(from: AircraftState): String?

    fun reduce(from: AircraftState): AircraftState
}

object InitialTransition : StateTransition {

    override fun canTransition(from: AircraftState): String? =
        if (!(from.isBoot() || from.isPowerOff())) {
            "Cannot reset to initial from MAV_STATE=${from.mavlink}"
        } else {
            null
        }

    override fun reduce(from: AircraftState): AircraftState =
        from.copy(mavlink = MAV_STATE.MAV_STATE_UNINIT)
}

object BootTransition : StateTransition {

    override fun canTransition(from: AircraftState): String? = if (!from.isUninitialized()) {
        "Already initialized"
    } else {
        null
    }

    override fun reduce(from: AircraftState): AircraftState =
        from.copy(mavlink = MAV_STATE.MAV_STATE_BOOT)
}

object StandbyTransition : StateTransition {

    override fun canTransition(from: AircraftState): String? = if (from.isFlying()) {
        "Aircraft is flying"
    } else {
        null
    }

    override fun reduce(from: AircraftState): AircraftState = from.copy(
        mavlink = MAV_STATE.MAV_STATE_STANDBY,
        landed = MAV_LANDED_STATE.MAV_LANDED_STATE_ON_GROUND
    )
}

object ArmTransition : StateTransition {

    override fun canTransition(from: AircraftState): String? = if (!from.isStandBy()) {
        "Not ready to arm"
    } else {
        null
    }

    override fun reduce(from: AircraftState): AircraftState =
        from.copy(mavlink = MAV_STATE.MAV_STATE_ACTIVE)
}

object TakeoffTransition : StateTransition {

    override fun canTransition(from: AircraftState): String? = when {
        !from.isArmed() -> "Not armed"
        !from.isOnTheGround() -> "Not on the ground"
        else -> null
    }

    override fun reduce(from: AircraftState): AircraftState =
        from.copy(landed = MAV_LANDED_STATE.MAV_LANDED_STATE_TAKEOFF)
}

object FlyingTransition : StateTransition {

    override fun canTransition(from: AircraftState): String? = when {
        !from.isArmed() -> "Not armed"
        else -> null
    }

    override fun reduce(from: AircraftState): AircraftState =
        from.copy(landed = MAV_LANDED_STATE.MAV_LANDED_STATE_IN_AIR)
}

object LandTransition : StateTransition {

    override fun canTransition(from: AircraftState): String? = when {
        !from.isArmed() -> "Not armed"
        !from.isFlying() -> "Not flying"
        else -> null
    }

    override fun reduce(from: AircraftState): AircraftState = from.copy(
        landed = MAV_LANDED_STATE.MAV_LANDED_STATE_LANDING
    )
}

object FlightTerminationTransition : StateTransition {

    override fun canTransition(from: AircraftState): String? = when {
        !from.isArmed() -> "Not armed"
        !from.isFlying() -> "Not flying"
        else -> null
    }

    override fun reduce(from: AircraftState): AircraftState = from.copy(
        mavlink = MAV_STATE.MAV_STATE_FLIGHT_TERMINATION
    )
}

object PowerOffTransition : StateTransition {

    override fun canTransition(from: AircraftState): String? = when {
        !from.isStandBy() -> "Still armed"
        !from.isOnTheGround() -> "Still flying"
        else -> null
    }

    override fun reduce(from: AircraftState): AircraftState = from.copy(
        mavlink = MAV_STATE.MAV_STATE_POWEROFF
    )
}

/**
 * Single source of truth for MAVLink + landed state transitions.
 * FSM / Reducer pattern.
 */
class AircraftStateMachine {

    var state = AircraftState()
        private set

    fun canDispatch(event: StateTransition): String? = event.canTransition(state)

    fun dispatch(event: StateTransition): AircraftState {
        state = event.reduce(state)
        return state
    }

    fun forceSet(target: AircraftState) {
        state = target
    }

    fun hasStateChanged(target: AircraftState): Boolean = state.mavlink != target.mavlink ||
        state.landed != target.landed

    fun setControlAuthority(controlAuthority: ControlAuthority): AircraftState {
        state = state.copy(controlAuthority = controlAuthority)
        return state
    }

    fun updateHomePosition(homeCoordinates: Coordinates3D): AircraftState {
        state = state.copy(homeCoordinates = homeCoordinates)
        return state
    }

    fun isMissionWaypoint() = state.controlAuthority == ControlAuthority.WAYPOINT_MISSION

    fun isTimelineCommand() = state.controlAuthority == ControlAuthority.TIMELINE_COMMAND

    fun isRemoteController() = state.controlAuthority == ControlAuthority.REMOTE_CONTROLLER

    fun isNewControlAuthority(authority: ControlAuthority) = state.controlAuthority != authority
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
    )
}

enum class ArduCopterFlightMode(val mode: Long, private val baseModeType: BaseModeType) {
    // https://ardupilot.org/dev/docs/apmcopter-adding-a-new-flight-mode.html
    STABILIZE(0, BaseModeType.MANUAL), // manual airframe angle with manual throttle
    ACRO(1, BaseModeType.MANUAL), // manual body-frame angular rate with manual throttle
    ALT_HOLD(2, BaseModeType.MANUAL), // manual airframe angle with automatic throttle
    AUTO(3, BaseModeType.AUTO), // fully automatic waypoint control using mission commands

    // fully automatic fly to coordinate or fly at velocity/direction using GCS immediate commands
    GUIDED(4, BaseModeType.GUIDED),

    // automatic horizontal acceleration with automatic throttle
    LOITER(5, BaseModeType.MANUAL),
    RTL(6, BaseModeType.AUTO), // automatic return to launching point
    CIRCLE(7, BaseModeType.MANUAL), // automatic circular flight with automatic throttle
    LAND(9, BaseModeType.AUTO), // automatic landing with horizontal position control
    DRIFT(11, BaseModeType.MANUAL), // semi-autonomous position, yaw and throttle control

    // manual earth-frame angular rate control with manual throttle
    SPORT(13, BaseModeType.MANUAL),
    FLIP(14, BaseModeType.MANUAL), // automatically flip the vehicle on the roll axis

    // automatically tune the vehicle's roll and pitch gains
    AUTOTUNE(15, BaseModeType.MANUAL),

    // automatic position hold with manual override, with automatic throttle
    POSHOLD(16, BaseModeType.MANUAL),
    BRAKE(17, BaseModeType.AUTO), // full-brake using inertial/GPS system, no pilot input

    // throw to launch mode using inertial/GPS system, no pilot input
    THROW(18, BaseModeType.AUTO),

    // automatic avoidance of obstacles in the macro scale - e.g. full-sized aircraft
    AVOID_ADSB(19, BaseModeType.AUTO),

    // guided mode but only accepts attitude and altitude
    GUIDED_NOGPS(20, BaseModeType.GUIDED),
    SMART_RTL(21, BaseModeType.AUTO), // SMART_RTL returns to home by retracing its steps

    // FLOWHOLD holds position with optical flow without rangefinder
    FLOWHOLD(22, BaseModeType.MANUAL),

    // follow attempts to follow another vehicle or ground station
    FOLLOW(23, BaseModeType.AUTO),

    // ZIGZAG mode is able to fly in a zigzag manner with predefined point A and point B
    ZIGZAG(24, BaseModeType.AUTO),

    // System ID mode produces automated system identification signals in the controllers
    SYSTEMID(25, BaseModeType.AUTO),
    AUTOROTATE(26, BaseModeType.AUTO); // Autonomous autorotation

    // NEWMODE(27, BaseModeType)

    val baseMode: Int
        get() = baseModeType.flags

    companion object {
        private val byValue = entries.associateBy { it.mode }

        fun from(value: Long): ArduCopterFlightMode? = byValue[value]
    }
}
