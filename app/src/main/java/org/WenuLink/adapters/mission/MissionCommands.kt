package org.WenuLink.adapters.mission

import com.MAVLink.common.msg_command_int
import com.MAVLink.common.msg_command_long
import com.MAVLink.common.msg_mission_item_int
import dji.common.gimbal.Attitude
import dji.common.model.LocationCoordinate2D
import dji.sdk.mission.timeline.actions.AircraftYawAction
import dji.sdk.mission.timeline.actions.GimbalAttitudeAction
import dji.sdk.mission.timeline.actions.GoHomeAction
import dji.sdk.mission.timeline.actions.GoToAction
import dji.sdk.mission.timeline.actions.LandAction
import dji.sdk.mission.timeline.actions.MissionAction
import dji.sdk.mission.timeline.actions.RecordVideoAction
import dji.sdk.mission.timeline.actions.ShootPhotoAction
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import org.WenuLink.adapters.MessageUtils
import org.WenuLink.adapters.aircraft.Coordinates3D
import org.WenuLink.commands.CommandResult
import org.WenuLink.commands.ICommand
import org.WenuLink.commands.UnitResult
import org.WenuLink.sdk.MissionActionManager
import org.WenuLink.sdk.MissionManager

sealed interface MissionCommand : ICommand<MissionHandler> {
    override fun validate(ctx: MissionHandler): UnitResult
    override suspend fun execute(ctx: MissionHandler): UnitResult
    override suspend fun onStop(ctx: MissionHandler)
}

data class UploadMissionCommand(
    private val assembler: MissionAssembler,
    private val flightSpeed: Float = 5f
) : MissionCommand {
    override fun validate(ctx: MissionHandler): UnitResult = when {
        ctx.state.canCreateMission() -> CommandResult.ok
        else -> CommandResult.error("Upload not ready")
    }

    override suspend fun execute(ctx: MissionHandler): UnitResult =
        suspendCancellableCoroutine { cont ->
            MissionManager.uploadMission(assembler.build(), flightSpeed) { _, error ->
                cont.resume(if (error == null) CommandResult.ok else CommandResult.error(error))
            }

            cont.invokeOnCancellation {
                // Add SDK cancel if available
            }
        }

    override suspend fun onStop(ctx: MissionHandler) = MissionManager.clearMission()
}

data object StartWaypointMission : MissionCommand {
    override fun validate(ctx: MissionHandler): UnitResult = when {
        ctx.state.canCreateMission() -> CommandResult.error("No mission found")
        ctx.state.isActive() -> CommandResult.error("Already started")
        ctx.state.canStartMission() -> CommandResult.ok
        else -> CommandResult.error("Not ready")
    }

    override suspend fun execute(ctx: MissionHandler): UnitResult =
        suspendCancellableCoroutine { cont ->
            MissionManager.startMission { error ->
                cont.resume(if (error == null) CommandResult.ok else CommandResult.error(error))
            }

            cont.invokeOnCancellation {
                // Add SDK cancel if available
            }
        }

    override suspend fun onStop(ctx: MissionHandler) = ctx.dispatchCommand(PauseWaypointMission)
}

data object PauseWaypointMission : MissionCommand {
    override fun validate(ctx: MissionHandler): UnitResult = when {
        ctx.state.canPauseMission() -> CommandResult.ok
        else -> CommandResult.error("Not started")
    }

    override suspend fun execute(ctx: MissionHandler): UnitResult =
        suspendCancellableCoroutine { cont ->
            MissionManager.pauseMission { error ->
                cont.resume(if (error == null) CommandResult.ok else CommandResult.error(error))
            }

            cont.invokeOnCancellation {
                // Add SDK cancel if available
            }
        }

    override suspend fun onStop(ctx: MissionHandler) { }
}

data object ResumeWaypointMission : MissionCommand {
    override fun validate(ctx: MissionHandler): UnitResult = when {
        ctx.state.canResumeMission() -> CommandResult.ok
        else -> CommandResult.error("Already in execution")
    }

    override suspend fun execute(ctx: MissionHandler): UnitResult =
        suspendCancellableCoroutine { cont ->
            MissionManager.resumeMission { error ->
                cont.resume(if (error == null) CommandResult.ok else CommandResult.error(error))
            }

            cont.invokeOnCancellation {
                // Add SDK cancel if available
            }
        }

    override suspend fun onStop(ctx: MissionHandler) { }
}

data object StopWaypointMission : MissionCommand {
    override fun validate(ctx: MissionHandler): UnitResult = when {
        !ctx.state.canCreateMission() -> CommandResult.ok
        else -> CommandResult.error("Nothing to stop")
    }

    override suspend fun execute(ctx: MissionHandler): UnitResult =
        suspendCancellableCoroutine { cont ->
            MissionManager.stopMission { error ->
                cont.resume(if (error == null) CommandResult.ok else CommandResult.error(error))
            }

            cont.invokeOnCancellation {
                // Add SDK cancel if available
            }
        }

    override suspend fun onStop(ctx: MissionHandler) { }
}

data object PauseActionCommand : MissionCommand {
    override fun validate(ctx: MissionHandler): UnitResult = when {
        !MissionActionManager.isRunning -> CommandResult.error("Timeline not running")
        else -> CommandResult.ok
    }

    override suspend fun execute(ctx: MissionHandler): UnitResult {
        MissionActionManager.pause()
        return CommandResult.ok
    }

    override suspend fun onStop(ctx: MissionHandler) { }
}

data object ResumeActionCommand : MissionCommand {
    override fun validate(ctx: MissionHandler): UnitResult = when {
        MissionActionManager.isRunning -> CommandResult.error("Timeline already running")
        else -> CommandResult.ok
    }

    override suspend fun execute(ctx: MissionHandler): UnitResult {
        MissionActionManager.resume()
        return CommandResult.ok
    }

    override suspend fun onStop(ctx: MissionHandler) { }
}

interface MissionActionCommand : MissionCommand {
    override fun validate(ctx: MissionHandler): UnitResult = when {
        MissionActionManager.isRunning -> CommandResult.error("Busy")
        else -> CommandResult.ok
    }

    override suspend fun onStop(ctx: MissionHandler) = MissionActionManager.stop()
}

data class DelayAction(val timeMillis: Long) : MissionActionCommand {
    companion object {
        fun fromParameters(
            param1: Float,
            param2: Float,
            param3: Float,
            param4: Float
        ): DelayAction {
            // NAV_DELAY
            val delay = param1.toInt()
            val hours = param2.toInt()
            val minutes = param3.toInt()
            val seconds = param4.toInt()

            val totalSeconds = if (delay != -1) {
                delay
            } else {
                (if (hours != -1) hours * 3600 else 0) +
                    (if (minutes != -1) minutes * 60 else 0) +
                    (if (seconds != -1) seconds else 0)
            }
            return DelayAction(totalSeconds * 1000L)
        }

        fun fromCommandLong(command: msg_command_long): DelayAction =
            fromParameters(command.param1, command.param2, command.param3, command.param4)

        fun fromMissionItem(missionItemMsg: msg_mission_item_int): DelayAction = fromParameters(
            missionItemMsg.param1,
            missionItemMsg.param2,
            missionItemMsg.param3,
            missionItemMsg.param4
        )
    }

    override fun validate(ctx: MissionHandler): UnitResult = when {
        ctx.state.isActive() -> CommandResult.error("Busy")
        else -> CommandResult.ok
    }

    override suspend fun execute(ctx: MissionHandler): UnitResult {
        delay(timeMillis)
        return CommandResult.ok
    }

    override suspend fun onStop(ctx: MissionHandler) { }
}

open class ActionCommand(val action: MissionAction) : MissionActionCommand {
    override suspend fun execute(ctx: MissionHandler): UnitResult =
        suspendCancellableCoroutine { cont ->
            MissionActionManager.clear()

            val error = MissionActionManager.schedule(action)

            if (error != null) {
                cont.resume(CommandResult.error("Error in $action: ${error.description}"))
                return@suspendCancellableCoroutine
            }

            val actionKey = MissionActionManager.onFinish(action::class) {
                cont.resume(CommandResult.ok)
            }

            MissionActionManager.startListener {
                // onError
                cont.resume(CommandResult.error("Action failed: $it"))
            }

            MissionActionManager.start()

            cont.invokeOnCancellation {
                // Add SDK cancel if available
                MissionActionManager.removeCallback(actionKey)
                MissionActionManager.stop()
            }
        }
}

data class RepositionAction(private val target: Coordinates3D, private val speed: Float) :
    ActionCommand(
        GoToAction(
            LocationCoordinate2D(target.lat, target.long),
            target.alt
        ).apply { flightSpeed = speed }
    ) {
    companion object {
        fun fromCommandInt(commandIntMsg: msg_command_int): RepositionAction {
            // DO_REPOSITION
            val targetCoordinates = MessageUtils.xyzMAVLink2Coordinates(
                commandIntMsg.x,
                commandIntMsg.y,
                commandIntMsg.z
            )
            val flightSpeed = if (commandIntMsg.param1 == -1f) 1f else commandIntMsg.param1

            return RepositionAction(targetCoordinates, flightSpeed)
        }

        fun fromMissionItem(
            missionItemMsg: msg_mission_item_int,
            missionSpeed: Float
        ): RepositionAction {
            // NAV_WAYPOINT
//            val holdSeconds = missionItemMsg.param1.toInt()
//            val acceptRadius = missionItemMsg.param2.toInt()
//            val passRadius = missionItemMsg.param3.toInt()
//            val yawRel = missionItemMsg.param4.toInt()
            val targetCoordinates =
                MessageUtils.xyzMAVLink2Coordinates(
                    missionItemMsg.x,
                    missionItemMsg.y,
                    missionItemMsg.z
                )

            return RepositionAction(targetCoordinates, missionSpeed)
        }
    }
}

data class ReturnAction(private val autoConfirmLanding: Boolean) :
    ActionCommand(
        GoHomeAction().apply {
            autoConfirmLandingEnabled = autoConfirmLanding
        }
    )

data class LandAction(private val autoConfirmLanding: Boolean) :
    ActionCommand(
        LandAction().apply {
            autoConfirmLandingEnabled = autoConfirmLanding
        }
    )

data class RotateAction(
    val angle: Float, // [-180, 180] deg
    private val velocity: Float? = null, // [0, 100] deg/s
    private val isAbsolute: Boolean = true
) : ActionCommand(
    if (velocity == null) {
        AircraftYawAction(angle, isAbsolute)
    } else {
        AircraftYawAction(angle, velocity)
    }
) {
    companion object {
        fun fromCommandLong(commandLongMsg: msg_command_long): RotateAction {
            // CONDITION_YAW
            var angle = commandLongMsg.param1 // [0, 360] deg
            var angularSpeed = commandLongMsg.param2 // deg/s
            val clockwiseDir = commandLongMsg.param3.toInt() == 1
            val absolute = commandLongMsg.param4.toInt() == 0

            // convert to compatible intervals
            angle = ((angle + 180) % 360) - 180
            if (absolute) return RotateAction(angle, null, true)

            // clip angular speed
            angularSpeed = angularSpeed.coerceIn(0f..100f)
            return RotateAction(angle, angularSpeed, false)
        }
    }
}

data class GimbalPitchAction(val angle: Float) :
    ActionCommand(
        GimbalAttitudeAction(
            Attitude(angle, Attitude.NO_ROTATION, Attitude.NO_ROTATION)
        )
    )

data class PhotoAction(private val number: Int, private val intervalSeconds: Int) :
    ActionCommand(
        when (number) {
            1 -> ShootPhotoAction.newShootSinglePhotoAction()
            0 -> ShootPhotoAction.newShootIntervalPhotoAction(Int.MAX_VALUE, intervalSeconds)
            else -> ShootPhotoAction.newShootIntervalPhotoAction(number, intervalSeconds)
        }
    )

data object StopPhotoAction : ActionCommand(
    ShootPhotoAction.newStopIntervalPhotoAction()
)

data class VideoAction(private val durationSeconds: Int = 0) :
    ActionCommand(
        if (durationSeconds > 0) {
            RecordVideoAction.newRecordVideoActionWithDuration(durationSeconds)
        } else {
            RecordVideoAction.newStartRecordVideoAction()
        }
    )

data object StopVideoAction : ActionCommand(
    RecordVideoAction.newStopRecordVideoAction()
)
