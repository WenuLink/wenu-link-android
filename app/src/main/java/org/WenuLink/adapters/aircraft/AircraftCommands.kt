package org.WenuLink.adapters.aircraft

import org.WenuLink.adapters.AircraftHandler
import org.WenuLink.adapters.ArduCopterFlightMode
import org.WenuLink.adapters.ArmTransition
import org.WenuLink.adapters.BootTransition
import org.WenuLink.adapters.Coordinates3D
import org.WenuLink.adapters.FlyingTransition
import org.WenuLink.adapters.LandTransition
import org.WenuLink.adapters.StandbyTransition
import org.WenuLink.adapters.TakeoffTransition

sealed interface AircraftCommand {
    suspend fun validate(ctx: AircraftHandler): String? = null
    suspend fun execute(ctx: AircraftHandler, onResult: (String?) -> Unit)
}

data class BootCommand(val timeout: Long = 5000L) : AircraftCommand {
    override suspend fun validate(ctx: AircraftHandler): String? =
        if (!ctx.isPowerOff) "Already booted" else null

    override suspend fun execute(ctx: AircraftHandler, onResult: (String?) -> Unit) {
        ctx.stateMachine.dispatch(BootTransition)
            .onSuccess {
                ctx.boot(timeout)
                onResult(null)
            }
            .onFailure { e ->
                onResult(e.message)
            }
    }
}

data class ArmCommand(val timeout: Long = 5000L) : AircraftCommand {

    override suspend fun validate(ctx: AircraftHandler): String? = when {
        !ctx.sensorsHealthy -> "Sensors failing"
        else -> null
    }
    override suspend fun execute(ctx: AircraftHandler, onResult: (String?) -> Unit) {
        ctx.stateMachine.dispatch(ArmTransition)
            .onSuccess {
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

                onResult(if (armed) null else "Unable to arm motors")
            }
            .onFailure { e ->
                onResult(e.message)
            }
    }
}

data class DisarmCommand(val timeout: Long = 5000L) : AircraftCommand {

    override suspend fun execute(ctx: AircraftHandler, onResult: (String?) -> Unit) {
        ctx.stateMachine.dispatch(StandbyTransition)
            .onSuccess {
                // continue execution
                ctx.disarmMotors()
                val disarmed = ctx.waitArmTransition(false, timeout)

                onResult(if (disarmed) null else "Unable to disarm motors")
            }
            .onFailure { e ->
                onResult(e.message)
            }
    }
}

data class TakeoffCommand(val initialAltitude: Float = 2f) : AircraftCommand {

    override suspend fun execute(ctx: AircraftHandler, onResult: (String?) -> Unit) {
        ctx.stateMachine.dispatch(TakeoffTransition)
            .onSuccess {
                // continue execution
                ctx.takeOff() // ctx.takeOff(initialAltitude)
                val isFlying = ctx.awaitFlightState(true)

                if (!isFlying) {
                    onResult("Unable to takeoff")
                    ctx.disarmMotors()
                    return
                }

                onResult(null)
            }
            .onFailure { e ->
                onResult(e.message)
            }
    }
}

data class LandCommand(val forceConfirmation: Boolean = true) : AircraftCommand {

    override suspend fun execute(ctx: AircraftHandler, onResult: (String?) -> Unit) {
        ctx.stateMachine.dispatch(LandTransition)
            .onSuccess {
                // continue execution
                ctx.land() // ctx.land(forceConfirmation)
                val onTheGround = ctx.awaitFlightState(false)

                if (!onTheGround) {
                    onResult("Unable to land")
                    return
                }

                ctx.dispatchCommand(DisarmCommand(), onResult)
            }
            .onFailure { e ->
                onResult(e.message)
            }
    }
}

data class RepositionCommand(val targetCoordinates: Coordinates3D, val speed: Float?) :
    AircraftCommand {

//    override suspend fun validate(ctx: AircraftHandler): String? =
//        if (!ctx.state.isFlying()) "Not flying" else null

    override suspend fun execute(ctx: AircraftHandler, onResult: (String?) -> Unit) {
        ctx.stateMachine.dispatch(FlyingTransition)
            .onSuccess {
                // continue execution
                ctx.doReposition(targetCoordinates, speed)
                // TODO: wait for reaching location
                onResult(null)
            }
            .onFailure { e ->
                onResult(e.message)
            }
    }
}

object GoHomeCommand : AircraftCommand {

//    override suspend fun validate(ctx: AircraftHandler): String? =
//        if (!ctx.state.isFlying()) "Not flying" else null

    override suspend fun execute(ctx: AircraftHandler, onResult: (String?) -> Unit) {
        ctx.stateMachine.dispatch(FlyingTransition)
            .onSuccess {
                // continue execution
                ctx.doGoHome()
                // TODO: wait for reaching location
                onResult(null)
            }
            .onFailure { e ->
                onResult(e.message)
            }
    }
}
