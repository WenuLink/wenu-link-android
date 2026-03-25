package org.WenuLink.adapters.aircraft

import org.WenuLink.adapters.commands.ICommand

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
        ctx.dispatchCommand(DisarmCommand()) { }
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
            ctx.dispatchCommand(LandCommand(true)) { }
            return "Unable to takeoff"
        }

        return null
    }

    override suspend fun onStop(ctx: AircraftHandler) {
        ctx.dispatchCommand(LandCommand(true)) { }
    }
}

data class LandCommand(val withLandingConfirmation: Boolean = true) : AircraftCommand {
    override fun validate(ctx: AircraftHandler): String? =
        ctx.stateMachine.canDispatch(LandTransition)

    override suspend fun execute(ctx: AircraftHandler): String? {
        // TODO: check if compatible with CancellableCoroutine
        ctx.stateMachine.dispatch(LandTransition)
        ctx.land() // ctx.land(forceConfirmation)
        val onTheGround = ctx.awaitFlightState(false)

        if (!onTheGround) return "Unable to land"

        ctx.dispatchCommand(DisarmCommand()) { }

        return null
    }

    override suspend fun onStop(ctx: AircraftHandler) {
        ctx.manualControl()
    }
}

data class RepositionCommand(val targetCoordinates: Coordinates3D, val speed: Float?) :
    AircraftCommand {
    override fun validate(ctx: AircraftHandler): String? =
        ctx.stateMachine.canDispatch(FlyingTransition)

    override suspend fun execute(ctx: AircraftHandler): String? {
        // TODO: check if compatible with CancellableCoroutine
        ctx.stateMachine.dispatch(FlyingTransition)
        ctx.doReposition(targetCoordinates, speed)
        // TODO: wait for reaching location?
        return null
    }

    override suspend fun onStop(ctx: AircraftHandler) {
        ctx.manualControl()
    }
}

object GoHomeCommand : AircraftCommand {

    override fun validate(ctx: AircraftHandler): String? =
        ctx.stateMachine.canDispatch(FlyingTransition)

    override suspend fun execute(ctx: AircraftHandler): String? {
        // TODO: check if compatible with CancellableCoroutine
        ctx.stateMachine.dispatch(FlyingTransition)
        ctx.doGoHome()
        // TODO: wait for reaching location?
        return null
    }

    override suspend fun onStop(ctx: AircraftHandler) {
        ctx.manualControl()
    }
}
