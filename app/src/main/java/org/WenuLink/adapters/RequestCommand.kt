package org.WenuLink.adapters

import org.WenuLink.adapters.aircraft.AircraftCommand
import org.WenuLink.adapters.aircraft.ArduCopterFlightMode
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

    suspend fun checkHomePosition(ctx: WenuLinkHandler): UnitResult {
        if (ctx.aircraft.state.isHomeSet()) return CommandResult.ok
        return ctx.dispatchAndAwait(
            WenuLinkCommand.Aircraft(SetHomePositionCommand(30_000L))
        )
    }

    override suspend fun execute(ctx: WenuLinkHandler): UnitResult {
        ctx.aircraft.dispatchTransition(transition)
        return CommandResult.ok
    }

    override suspend fun onStop(ctx: WenuLinkHandler) = ctx.manualControl()
}

data class RequestLand(val withLandingConfirmation: Boolean = true, val timeout: Long = 15_000L) :
    RequestTransition(LandTransition) {
    override suspend fun execute(ctx: WenuLinkHandler): UnitResult {
        super.execute(ctx)

        val authorityResult = ctx.dispatchControlAuthority(ControlAuthorityType.TIMELINE_COMMAND)
        if (authorityResult.hasError) return authorityResult

        val landingResult = ctx.dispatchAndAwait(WenuLinkCommand.Mission(LandAction(true)))
        if (landingResult.hasError) return landingResult

        // Wait for ground
        val onGround = ctx.aircraft.waitFlightState(false, timeout)
        if (!onGround) return CommandResult.error("Vehicle still flying! Unable to disarm yet")
        // Wait for disarm
        return ctx.dispatchAndAwait(WenuLinkCommand.Aircraft(DisarmCommand(timeout)))
    }
}

data class RequestTakeoff(val altitude: Float = 2f, val timeout: Long = 15_000L) :
    RequestTransition(TakeoffTransition) {
    override suspend fun execute(ctx: WenuLinkHandler): UnitResult {
        val homeResult = checkHomePosition(ctx)
        if (homeResult.hasError) return homeResult

        super.execute(ctx)

        val authorityResult = ctx.dispatchControlAuthority(ControlAuthorityType.TIMELINE_COMMAND)
        if (authorityResult.hasError) return authorityResult

        val takeoffResult = ctx.dispatchAndAwait(WenuLinkCommand.Aircraft(TakeoffCommand(timeout)))
        if (takeoffResult.hasError) return takeoffResult

        val coordinates = ctx.aircraft.currentCoordinates
            ?: return CommandResult.error("No aircraft position available")

        return ctx.dispatchAndAwait(
            WenuLinkCommand.Mission(
                RepositionAction(coordinates.copy(alt = altitude), ctx.mission.flightSpeed)
            )
        )
    }
}

data class RequestStartMission(
    private val startSequence: Int,
    private val endSequence: Int,
    private val alreadyArmed: Boolean = false
) : RequestTransition(
    if (alreadyArmed) {
        TakeoffTransition
    } else {
        ArmTransition
    }
) {
    override suspend fun execute(ctx: WenuLinkHandler): UnitResult {
        val homeResult = checkHomePosition(ctx)
        if (homeResult.hasError) return homeResult

        // Handle initial transitions
        super.execute(ctx)

        val authorityResult = ctx.dispatchControlAuthority(ControlAuthorityType.WAYPOINT_MISSION)
        if (authorityResult.hasError) return authorityResult

        // Triggers SDK start function
        ctx.mission.setStartSequence(startSequence)
        val startResult = ctx.dispatchAndAwait(WenuLinkCommand.Mission(StartWaypointMission))
        if (startResult.hasError) return startResult

        // Wait arm and takeoff
        val takeoffOk = ctx.aircraft.waitFlightState(true, 15_000L)
        if (!takeoffOk) return CommandResult.error("Vehicle did not takeoff!")

        // Wait initial altitude for mission start (5min top)
        val initOk = ctx.mission.waitMissionStart(300_000L)
        if (!initOk) return CommandResult.error("Mission did not start!")

        // Handle final transition
        ctx.aircraft.dispatchTransition(FlyingTransition)

        return CommandResult.ok
    }
}

open class RequestMissionAction(private val action: MissionActionCommand) :
    RequestTransition(FlyingTransition) {
    override suspend fun execute(ctx: WenuLinkHandler): UnitResult {
        super.execute(ctx)

        val authorityResult = ctx.dispatchControlAuthority(ControlAuthorityType.TIMELINE_COMMAND)
        if (authorityResult.hasError) return authorityResult

        val actionResult = ctx.dispatchAndAwait(WenuLinkCommand.Mission(action))
        if (actionResult.hasError) return authorityResult

        return ctx.dispatchControlAuthority(ControlAuthorityType.REMOTE_CONTROLLER)
    }
}

data class RequestGoHome(private val autoConfirmLanding: Boolean) :
    RequestMissionAction(ReturnAction(autoConfirmLanding))
