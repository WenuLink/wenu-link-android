package org.WenuLink.adapters.aircraft

import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import org.WenuLink.adapters.commands.ICommand
import org.WenuLink.adapters.mission.LandCommand
import org.WenuLink.adapters.mission.RepositionCommand
import org.WenuLink.adapters.mission.ReturnToHomeCommand
import org.WenuLink.adapters.mission.StartWaypointMission

sealed interface AircraftCommand : ICommand<AircraftHandler> {
    override fun validate(ctx: AircraftHandler): String?
    override suspend fun execute(ctx: AircraftHandler): String?
    override suspend fun onStop(ctx: AircraftHandler)
}

data class BootCommand(val timeout: Long = 5000L) : AircraftCommand {
    override fun validate(ctx: AircraftHandler): String? {
        if (!ctx.isPowerOff) return "Already booted"
        return ctx.stateMachine.canDispatch(BootTransition)
    }

    override suspend fun execute(ctx: AircraftHandler): String? {
        // TODO: check if compatible with CancellableCoroutine
        ctx.stateMachine.dispatch(BootTransition)
        return ctx.boot(timeout)
    }

    override suspend fun onStop(ctx: AircraftHandler) {
        ctx.stateMachine.dispatch(InitialTransition)
    }
}

data class ArmCommand(val timeout: Long = 5000L) : AircraftCommand {

    override fun validate(ctx: AircraftHandler): String? {
        if (!ctx.sensorsHealthy) return "Sensors failing"
        return ctx.stateMachine.canDispatch(ArmTransition)
    }

    override suspend fun execute(ctx: AircraftHandler): String? {
        // TODO: check if compatible with CancellableCoroutine
        ctx.stateMachine.dispatch(ArmTransition)
        // TODO: update according to each mode
        // https://ardupilot.org/copter/docs/arming_the_motors.html
        val armed = if (ctx.copterFlightMode == ArduCopterFlightMode.STABILIZE) {
            // Manual takeoff
            ctx.armMotors()
            ctx.waitArmTransition(true, timeout)
        } else {
            // Automatic takeoff only must wait for state changes
            true
        }

        return if (armed) null else "Unable to arm motors"
    }

    override suspend fun onStop(ctx: AircraftHandler) {
        ctx.dispatchCommand(DisarmCommand())
    }
}

data class DisarmCommand(val timeout: Long = 5000L) : AircraftCommand {

    override fun validate(ctx: AircraftHandler): String? =
        ctx.stateMachine.canDispatch(StandbyTransition)

    override suspend fun execute(ctx: AircraftHandler): String? {
        // TODO: check if compatible with CancellableCoroutine
        ctx.stateMachine.dispatch(StandbyTransition)
        ctx.disarmMotors()
        val disarmed = ctx.waitArmTransition(false, timeout)

        return if (disarmed) null else "Unable to disarm motors"
    }

    override suspend fun onStop(ctx: AircraftHandler) { } // silently omit
}

data class TakeoffCommand(val initialAltitude: Float = 2f) : AircraftCommand {

    override fun validate(ctx: AircraftHandler): String? =
        ctx.stateMachine.canDispatch(TakeoffTransition)

    override suspend fun execute(ctx: AircraftHandler): String? {
        // TODO: check if compatible with CancellableCoroutine
        ctx.stateMachine.dispatch(TakeoffTransition)
        ctx.takeOff() // ctx.takeOff(initialAltitude)
        val isFlying = ctx.awaitFlightState(true)

        if (!isFlying) {
            ctx.dispatchCommand(RequestLand(true))
            return "Unable to takeoff"
        }

        return null
    }

    override suspend fun onStop(ctx: AircraftHandler) {
        ctx.dispatchCommand(RequestLand(true))
    }
}

data class RequestLand(val withLandingConfirmation: Boolean = true) : AircraftCommand {
    override fun validate(ctx: AircraftHandler): String? =
        ctx.stateMachine.canDispatch(LandTransition)

    override suspend fun execute(ctx: AircraftHandler): String? =
        suspendCancellableCoroutine { cont ->
            ctx.stateMachine.dispatch(LandTransition)
            ctx.controlTransition(ControlAuthority.TIMELINE_COMMAND)

            ctx.mission.dispatchCommand(LandCommand(true)) { error ->
                if (error != null) {
                    ctx.dispatchCommand(DisarmCommand(), cont::resume)
                } else {
                    cont.resume(error)
                }
            }
            cont.invokeOnCancellation {
                // Add SDK cancel if available
                ctx.manualControl()
            }
        }

    override suspend fun onStop(ctx: AircraftHandler) {
        ctx.manualControl()
    }
}

data class ShutdownCommand(val withTransitionCheck: Boolean = true) : AircraftCommand {
    override fun validate(ctx: AircraftHandler): String? = when {
        ctx.isPowerOff -> "Already power off"
        !withTransitionCheck -> null
        else -> ctx.stateMachine.canDispatch(PowerOffTransition)
    }

    override suspend fun execute(ctx: AircraftHandler): String? {
        // TODO: check if compatible with CancellableCoroutine
        if (withTransitionCheck) ctx.stateMachine.dispatch(PowerOffTransition)
        return ctx.shutdown()
    }

    override suspend fun onStop(ctx: AircraftHandler) {
        ctx.stateMachine.dispatch(InitialTransition)
    }
}

interface FlyingRequestCommand : AircraftCommand {
    override fun validate(ctx: AircraftHandler): String? =
        ctx.stateMachine.canDispatch(FlyingTransition)

    override suspend fun execute(ctx: AircraftHandler): String?

    override suspend fun onStop(ctx: AircraftHandler) = ctx.manualControl()
}

data class RequestReposition(val targetCoordinates: Coordinates3D, val speed: Float? = null) :
    FlyingRequestCommand {

    override suspend fun execute(ctx: AircraftHandler): String? =
        suspendCancellableCoroutine { cont ->
            ctx.stateMachine.dispatch(FlyingTransition)
            ctx.controlTransition(ControlAuthority.TIMELINE_COMMAND)

            ctx.mission.dispatchCommand(
                RepositionCommand(targetCoordinates, speed ?: ctx.mission.flightSpeed)
            ) { error ->
                cont.resume(error)
            }

            cont.invokeOnCancellation {
                // Add SDK cancel if available
                ctx.manualControl()
            }
        }
}

data class RequestStartMission(private val startSequence: Int = 0) : FlyingRequestCommand {

    override fun validate(ctx: AircraftHandler): String? =
        ctx.stateMachine.canDispatch(FlyingTransition)

    override suspend fun execute(ctx: AircraftHandler): String? =
        suspendCancellableCoroutine { cont ->
            ctx.stateMachine.dispatch(FlyingTransition)
            ctx.controlTransition(ControlAuthority.WAYPOINT_MISSION)

            ctx.mission.setItemSequenceIndex(startSequence)
            ctx.mission.dispatchCommand(StartWaypointMission) { error ->
                cont.resume(error)
            }

            cont.invokeOnCancellation {
                // Add SDK cancel if available
                ctx.manualControl()
            }
        }

    override suspend fun onStop(ctx: AircraftHandler) {
        ctx.manualControl()
    }
}

object RequestGoHome : AircraftCommand {

    override fun validate(ctx: AircraftHandler): String? =
        ctx.stateMachine.canDispatch(FlyingTransition)

    override suspend fun execute(ctx: AircraftHandler): String? =
        suspendCancellableCoroutine { cont ->
            ctx.stateMachine.dispatch(FlyingTransition)
            ctx.controlTransition(ControlAuthority.TIMELINE_COMMAND)

            ctx.mission.dispatchCommand(ReturnToHomeCommand(true)) { error ->
                cont.resume(error)
            }

            cont.invokeOnCancellation {
                // Add SDK cancel if available
                ctx.manualControl()
            }
        }

    override suspend fun onStop(ctx: AircraftHandler) {
        ctx.manualControl()
    }
}
