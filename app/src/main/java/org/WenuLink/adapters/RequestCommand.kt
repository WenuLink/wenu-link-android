package org.WenuLink.adapters

import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import org.WenuLink.adapters.aircraft.AircraftCommand
import org.WenuLink.adapters.aircraft.ArmTransition
import org.WenuLink.adapters.aircraft.ControlAuthorityType
import org.WenuLink.adapters.aircraft.DisarmCommand
import org.WenuLink.adapters.aircraft.FlyingTransition
import org.WenuLink.adapters.aircraft.LandTransition
import org.WenuLink.adapters.aircraft.SetHomePositionCommand
import org.WenuLink.adapters.aircraft.StateTransition
import org.WenuLink.adapters.aircraft.TakeoffCommand
import org.WenuLink.adapters.aircraft.TakeoffTransition
import org.WenuLink.adapters.camera.CameraCommand
import org.WenuLink.adapters.mission.LandAction
import org.WenuLink.adapters.mission.MissionActionCommand
import org.WenuLink.adapters.mission.MissionCommand
import org.WenuLink.adapters.mission.RepositionAction
import org.WenuLink.adapters.mission.ReturnAction
import org.WenuLink.adapters.mission.StartWaypointMission
import org.WenuLink.commands.CommandResult
import org.WenuLink.commands.ICommand
import org.WenuLink.commands.UnitResult

sealed interface WenuLinkCommand {
    data class Aircraft(val command: AircraftCommand) : WenuLinkCommand
    data class Mission(val command: MissionCommand) : WenuLinkCommand
    data class Camera(val command: CameraCommand) : WenuLinkCommand
    data class Request(val command: RequestCommand) : WenuLinkCommand
}

sealed interface RequestCommand : ICommand<WenuLinkHandler> {
    override fun validate(ctx: WenuLinkHandler): UnitResult
    override suspend fun execute(ctx: WenuLinkHandler): UnitResult
    override suspend fun onStop(ctx: WenuLinkHandler)
}

open class RequestTransition(open val transition: StateTransition) : RequestCommand {
    override fun validate(ctx: WenuLinkHandler): UnitResult =
        ctx.aircraft.canDispatchTransition(transition)

    override suspend fun execute(ctx: WenuLinkHandler): UnitResult {
        ctx.aircraft.dispatchTransition(transition)
        return if (ctx.aircraft.checkTransition(transition)) {
            CommandResult.ok
        } else {
            CommandResult.error("Unsuccessful state transition")
        }
    }

    override suspend fun onStop(ctx: WenuLinkHandler) = ctx.manualControl()
}

data class RequestLand(val withLandingConfirmation: Boolean = true) :
    RequestTransition(LandTransition) {
    override suspend fun execute(ctx: WenuLinkHandler): UnitResult {
        val transitionResult = super.execute(ctx)
        if (transitionResult.hasError) return transitionResult

        ctx.dispatchControlAuthority(ControlAuthorityType.TIMELINE_COMMAND)

        return suspendCancellableCoroutine { cont ->
            ctx.dispatchCommand(WenuLinkCommand.Mission(LandAction(true))) { result ->
                if (result.hasError) {
                    cont.resume(result)
                    return@dispatchCommand
                }
                ctx.dispatchCommand(
                    WenuLinkCommand.Aircraft(DisarmCommand()),
                    cont::resume
                )
            }

            cont.invokeOnCancellation { ctx.manualControl() }
        }
    }
}

data class RequestTakeoff(
    val altitude: Float = 2f,
    val speed: Float = 2f,
    val timeout: Long = 15_000L
) : RequestTransition(TakeoffTransition) {
    override suspend fun execute(ctx: WenuLinkHandler): UnitResult {
        val transitionResult = super.execute(ctx)
        if (transitionResult.hasError) return transitionResult

        ctx.dispatchControlAuthority(ControlAuthorityType.TIMELINE_COMMAND)

        return suspendCancellableCoroutine { cont ->
            ctx.dispatchCommand(
                WenuLinkCommand.Aircraft(SetHomePositionCommand(timeout))
            ) { homeResult ->
                if (homeResult.hasError) {
                    cont.resume(homeResult)
                    return@dispatchCommand
                }

                ctx.dispatchCommand(
                    WenuLinkCommand.Aircraft(TakeoffCommand(timeout))
                ) { takeoffResult ->
                    if (takeoffResult.hasError) {
                        cont.resume(takeoffResult)
                        return@dispatchCommand
                    }

                    val coordinates = ctx.aircraft.currentCoordinates
                        ?: return@dispatchCommand cont.resume(
                            CommandResult.error("No aircraft position available")
                        )

                    ctx.dispatchCommand(
                        WenuLinkCommand.Mission(
                            RepositionAction(coordinates.copy(alt = altitude), speed)
                        ),
                        cont::resume
                    )
                }
            }

            cont.invokeOnCancellation {
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
    override suspend fun execute(ctx: WenuLinkHandler): UnitResult {
        val transitionResult = super.execute(ctx)
        if (transitionResult.hasError) return transitionResult

        ctx.dispatchControlAuthority(ControlAuthorityType.WAYPOINT_MISSION)

        return suspendCancellableCoroutine { cont ->
            ctx.dispatchCommand(
                WenuLinkCommand.Aircraft(SetHomePositionCommand())
            ) { homeResult ->
                if (homeResult.hasError) {
                    cont.resume(homeResult)
                    return@dispatchCommand
                }

                ctx.mission.setStartSequence(startSequence)

                ctx.dispatchCommand(
                    WenuLinkCommand.Mission(StartWaypointMission),
                    cont::resume
                )
            }

            cont.invokeOnCancellation {
                // Add SDK cancel if available
                ctx.manualControl()
            }
        }
    }
}

open class RequestMissionAction(private val action: MissionActionCommand) :
    RequestTransition(FlyingTransition) {
    override suspend fun execute(ctx: WenuLinkHandler): UnitResult {
        val transitionResult = super.execute(ctx)
        if (transitionResult.hasError) return transitionResult

        ctx.dispatchControlAuthority(ControlAuthorityType.WAYPOINT_MISSION)

        return suspendCancellableCoroutine { cont ->

            ctx.dispatchCommand(
                WenuLinkCommand.Mission(action),
                cont::resume
            )

            cont.invokeOnCancellation {
                // Add SDK cancel if available
                ctx.manualControl()
            }
        }
    }
}

data class RequestGoHome(private val autoConfirmLanding: Boolean) :
    RequestMissionAction(ReturnAction(autoConfirmLanding))
