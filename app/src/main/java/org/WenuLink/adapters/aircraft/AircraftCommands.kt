package org.WenuLink.adapters.aircraft

import org.WenuLink.commands.ICommand

sealed interface AircraftCommand : ICommand<AircraftHandler> {
    override fun validate(ctx: AircraftHandler): String?
    override suspend fun execute(ctx: AircraftHandler): String?
    override suspend fun onStop(ctx: AircraftHandler)
}

data class BootCommand(val timeout: Long = 5000L) : AircraftCommand {
    override fun validate(ctx: AircraftHandler): String? {
        if (!ctx.isPowerOff) return "Already booted"
        return ctx.canDispatchTransition(BootTransition)
    }

    override suspend fun execute(ctx: AircraftHandler): String? {
        // TODO: check if compatible with CancellableCoroutine
        ctx.dispatchTransition(BootTransition)
        return ctx.boot(timeout)
    }

    override suspend fun onStop(ctx: AircraftHandler) {
        ctx.dispatchTransition(InitialTransition)
    }
}

data class ArmCommand(val timeout: Long = 5000L) : AircraftCommand {
    override fun validate(ctx: AircraftHandler): String? {
        if (!ctx.sensorsHealthy) return "Sensors failing"
        return ctx.canDispatchTransition(ArmTransition)
    }

    override suspend fun execute(ctx: AircraftHandler): String? {
        // TODO: check if compatible with CancellableCoroutine
        ctx.dispatchTransition(ArmTransition)
        // TODO: update according to each mode
        // https://ardupilot.org/copter/docs/arming_the_motors.html
        val armed = if (ctx.state.flightMode == ArduCopterFlightMode.STABILIZE) {
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
        ctx.canDispatchTransition(StandbyTransition)

    override suspend fun execute(ctx: AircraftHandler): String? {
        // TODO: check if compatible with CancellableCoroutine
        ctx.dispatchTransition(StandbyTransition)
        ctx.disarmMotors()
        val disarmed = ctx.waitArmTransition(false, timeout)

        return if (disarmed) null else "Unable to disarm motors"
    }

    override suspend fun onStop(ctx: AircraftHandler) { } // silently omit
}

data class TakeoffCommand(val initialAltitude: Float = 2f) : AircraftCommand {
    override fun validate(ctx: AircraftHandler): String? =
        ctx.canDispatchTransition(TakeoffTransition)

    override suspend fun execute(ctx: AircraftHandler): String? {
        // TODO: check if compatible with CancellableCoroutine
        ctx.dispatchTransition(TakeoffTransition)
        ctx.takeOff() // ctx.takeOff(initialAltitude)
        val isFlying = ctx.awaitFlightState(true)

        if (!isFlying) {
            ctx.dispatchCommand(DisarmCommand())
            return "Unable to takeoff"
        }

        return null
    }

    override suspend fun onStop(ctx: AircraftHandler) {
        ctx.dispatchCommand(DisarmCommand())
    }
}

data class ShutdownCommand(val withTransitionCheck: Boolean = true) : AircraftCommand {
    override fun validate(ctx: AircraftHandler): String? = when {
        ctx.isPowerOff -> "Already power off"
        !withTransitionCheck -> null
        else -> ctx.canDispatchTransition(PowerOffTransition)
    }

    override suspend fun execute(ctx: AircraftHandler): String? {
        // TODO: check if compatible with CancellableCoroutine
        if (withTransitionCheck) ctx.dispatchTransition(PowerOffTransition)
        return ctx.shutdown()
    }

    override suspend fun onStop(ctx: AircraftHandler) {
        ctx.dispatchTransition(InitialTransition)
    }
}
