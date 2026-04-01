package org.WenuLink.adapters

import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import org.WenuLink.adapters.aircraft.AircraftCommand
import org.WenuLink.adapters.aircraft.ArmTransition
import org.WenuLink.adapters.aircraft.ControlAuthority
import org.WenuLink.adapters.aircraft.Coordinates3D
import org.WenuLink.adapters.aircraft.DisarmCommand
import org.WenuLink.adapters.aircraft.FlyingTransition
import org.WenuLink.adapters.aircraft.LandTransition
import org.WenuLink.adapters.aircraft.StateTransition
import org.WenuLink.adapters.camera.CameraCommand
import org.WenuLink.adapters.mission.LandCommand
import org.WenuLink.adapters.mission.MissionCommand
import org.WenuLink.adapters.mission.RepositionCommand
import org.WenuLink.adapters.mission.ReturnToHomeCommand
import org.WenuLink.adapters.mission.StartWaypointMission
import org.WenuLink.commands.ICommand

sealed interface WenuLinkCommand {
    data class Aircraft(val command: AircraftCommand) : WenuLinkCommand
    data class Mission(val command: MissionCommand) : WenuLinkCommand
    data class Camera(val command: CameraCommand) : WenuLinkCommand
    data class Request(val command: RequestCommand) : WenuLinkCommand
}

sealed interface RequestCommand : ICommand<WenuLinkHandler> {
    override fun validate(ctx: WenuLinkHandler): String?
    override suspend fun execute(ctx: WenuLinkHandler): String?
    override suspend fun onStop(ctx: WenuLinkHandler)
}

open class RequestTransition(open val transition: StateTransition) :
    RequestCommand {

    override fun validate(ctx: WenuLinkHandler): String? =
        ctx.aircraft.canDispatchTransition(transition)

    override suspend fun execute(ctx: WenuLinkHandler): String? =
        suspendCancellableCoroutine { cont ->
            val prevState = ctx.aircraft.state.copy()
            ctx.aircraft.dispatchTransition(transition)
            val newState = ctx.aircraft.state.copy()

            cont.resume(
                if (prevState == newState) "State not changed" else null
            )

            cont.invokeOnCancellation {
                ctx.manualControl()
            }
        }

    override suspend fun onStop(ctx: WenuLinkHandler) {
        ctx.manualControl()
    }
}

data class RequestLand(val withLandingConfirmation: Boolean = true) :
    RequestTransition(LandTransition) {

    override suspend fun execute(ctx: WenuLinkHandler): String? {
        val transitionError = super.execute(ctx)
        if (transitionError != null) return transitionError

        ctx.controlTransition(ControlAuthority.TIMELINE_COMMAND)

        return suspendCancellableCoroutine { cont ->
            ctx.dispatchCommand(WenuLinkCommand.Mission(LandCommand(true))) { error ->
                if (error == null) {
                    ctx.dispatchCommand(
                        WenuLinkCommand.Aircraft(DisarmCommand()),
                        cont::resume
                    )
                } else {
                    cont.resume(error)
                }
            }

            cont.invokeOnCancellation {
                ctx.manualControl()
            }
        }
    }
}

data class RequestReposition(val targetCoordinates: Coordinates3D, val speed: Float?) :
    RequestTransition(FlyingTransition) {

    override suspend fun execute(ctx: WenuLinkHandler): String? {
        val transitionError = super.execute(ctx)
        if (transitionError != null) return transitionError

        ctx.controlTransition(ControlAuthority.TIMELINE_COMMAND)

        return suspendCancellableCoroutine { cont ->

            ctx.dispatchCommand(
                WenuLinkCommand.Mission(
                    RepositionCommand(targetCoordinates, speed ?: ctx.mission.flightSpeed)
                ),
                cont::resume
            )

            cont.invokeOnCancellation {
                // Add SDK cancel if available
                ctx.manualControl()
            }
        }
    }
}

object RequestGoHome : RequestTransition(FlyingTransition) {

    override suspend fun execute(ctx: WenuLinkHandler): String? {
        val transitionError = super.execute(ctx)
        if (transitionError != null) return transitionError

        ctx.controlTransition(ControlAuthority.TIMELINE_COMMAND)

        return suspendCancellableCoroutine { cont ->
            ctx.dispatchCommand(
                WenuLinkCommand.Mission(ReturnToHomeCommand(true)),
                cont::resume
            )
            cont.invokeOnCancellation {
                // Add SDK cancel if available
                ctx.manualControl()
            }
        }
    }
}

data class RequestStartMission(
    private val startSequence: Int,
    private val endSequence: Int,
    private val alreadyArmed: Boolean = false
) : RequestTransition(
    if (alreadyArmed) {
        FlyingTransition
    } else {
        ArmTransition
    }
) {
    override suspend fun execute(ctx: WenuLinkHandler): String? {
        val transitionError = super.execute(ctx)
        if (transitionError != null) return transitionError

        ctx.controlTransition(ControlAuthority.WAYPOINT_MISSION)

        return suspendCancellableCoroutine { cont ->

            ctx.mission.setItemSequenceIndex(startSequence)
            ctx.dispatchCommand(
                WenuLinkCommand.Mission(StartWaypointMission),
                cont::resume
            )

            cont.invokeOnCancellation {
                // Add SDK cancel if available
                ctx.manualControl()
            }
        }
    }
}
