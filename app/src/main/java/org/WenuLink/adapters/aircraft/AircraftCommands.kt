package org.WenuLink.adapters.aircraft

import org.WenuLink.commands.CommandResult
import org.WenuLink.commands.ICommand
import org.WenuLink.commands.UnitResult
import org.WenuLink.sdk.FCManager

sealed interface AircraftCommand : ICommand<AircraftHandler> {
    override fun validate(ctx: AircraftHandler): UnitResult
    override suspend fun execute(ctx: AircraftHandler): UnitResult
    override suspend fun onStop(ctx: AircraftHandler)
}

data class BootCommand(val timeout: Long = 5000L) : AircraftCommand {
    override fun validate(ctx: AircraftHandler): UnitResult = when {
        !ctx.isPowerOff -> CommandResult.error("Already booted")
        else -> ctx.canDispatchTransition(BootTransition)
    }

    override suspend fun execute(ctx: AircraftHandler): UnitResult {
        // TODO: check if compatible with CancellableCoroutine
        ctx.dispatchTransition(BootTransition)
        return ctx.boot(timeout)
            ?.let { CommandResult.error(it) }
            ?: CommandResult.ok
    }

    override suspend fun onStop(ctx: AircraftHandler) {
        ctx.dispatchTransition(InitialTransition)
    }
}

data class ArmCommand(val timeout: Long = 5000L) : AircraftCommand {
    override fun validate(ctx: AircraftHandler): UnitResult = when {
        !ctx.sensorsHealthy -> CommandResult.error("Sensors failing")
        else -> ctx.canDispatchTransition(ArmTransition)
    }

    override suspend fun execute(ctx: AircraftHandler): UnitResult {
        // TODO: check if compatible with CancellableCoroutine
        ctx.dispatchTransition(ArmTransition)
        // TODO: update according to each mode
        // https://ardupilot.org/copter/docs/arming_the_motors.html
        if (ctx.state.flightMode == ArduCopterFlightMode.STABILIZE) {
            // Manual takeoff
            ctx.armMotors()
            if (!ctx.waitArmTransition(true, timeout)) {
                return CommandResult.error("Unable to arm motors")
            }
        } else {
            // Automatic takeoff only. Must wait for state changes
        }

        return CommandResult.ok
    }

    override suspend fun onStop(ctx: AircraftHandler) {
        ctx.dispatchCommand(DisarmCommand())
    }
}

data class DisarmCommand(val timeout: Long = 5000L) : AircraftCommand {
    override fun validate(ctx: AircraftHandler): UnitResult =
        ctx.canDispatchTransition(StandbyTransition)

    override suspend fun execute(ctx: AircraftHandler): UnitResult {
        // TODO: check if compatible with CancellableCoroutine
        ctx.dispatchTransition(StandbyTransition)
        ctx.disarmMotors()
        return if (ctx.waitArmTransition(false, timeout)) {
            CommandResult.ok
        } else {
            CommandResult.error("Unable to disarm motors")
        }
    }

    override suspend fun onStop(ctx: AircraftHandler) { } // silently omit
}

data class TakeoffCommand(val timeout: Long = 15_000L) : AircraftCommand {
    override fun validate(ctx: AircraftHandler): UnitResult =
        ctx.canDispatchTransition(TakeoffTransition)

    override suspend fun execute(ctx: AircraftHandler): UnitResult {
        // TODO: check if compatible with CancellableCoroutine
        ctx.dispatchTransition(TakeoffTransition)
        ctx.takeOff()
        return if (ctx.waitFlightState(true, timeout)) {
            CommandResult.ok
        } else {
            ctx.dispatchCommand(DisarmCommand())
            CommandResult.error("Unable to takeoff")
        }
    }

    override suspend fun onStop(ctx: AircraftHandler) {
        ctx.dispatchCommand(DisarmCommand())
    }
}

data class ShutdownCommand(val withTransitionCheck: Boolean = true) : AircraftCommand {
    override fun validate(ctx: AircraftHandler): UnitResult = when {
        ctx.isPowerOff -> CommandResult.error("Already power off")
        !withTransitionCheck -> CommandResult.ok
        else -> ctx.canDispatchTransition(PowerOffTransition)
    }

    override suspend fun execute(ctx: AircraftHandler): UnitResult {
        // TODO: check if compatible with CancellableCoroutine
        if (withTransitionCheck) ctx.dispatchTransition(PowerOffTransition)
        ctx.shutdown()
        ctx.dispatchTransition(InitialTransition)
        return CommandResult.ok
    }

    override suspend fun onStop(ctx: AircraftHandler) {
        ctx.dispatchTransition(InitialTransition)
    }
}

data class SetHomePositionCommand(val timeout: Long = 30_000L) : AircraftCommand {
    override fun validate(ctx: AircraftHandler): UnitResult = CommandResult.ok

    override suspend fun execute(ctx: AircraftHandler): UnitResult {
        if (ctx.state.isHomeSet()) return CommandResult.ok
        if (ctx.waitHomeSet(timeout)) {
            return CommandResult.error("Home position not acquired after $timeout ms")
        }

        FCManager.getHomePosition()?.let { ctx.stateMachine.updateHomePosition(it) }

        return CommandResult.ok
    }

    override suspend fun onStop(ctx: AircraftHandler) { } // silently omit
}
