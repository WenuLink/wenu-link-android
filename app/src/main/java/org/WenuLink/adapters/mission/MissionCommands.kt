package org.WenuLink.adapters.mission

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
import org.WenuLink.adapters.aircraft.Coordinates3D
import org.WenuLink.commands.CommandResult
import org.WenuLink.commands.ICommand
import org.WenuLink.commands.UnitResult
import org.WenuLink.mavlink.messages.ConditionYawMessage
import org.WenuLink.mavlink.messages.DoRepositionCommandInt
import org.WenuLink.mavlink.messages.ImageStartCaptureMissionItem
import org.WenuLink.mavlink.messages.NavDelayMessage
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
        ctx.state.isActive() -> CommandResult.ok
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
        ctx.state.isPaused() -> CommandResult.ok
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
        MissionActionManager.isPaused -> CommandResult.error("Timeline already paused")
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
        !MissionActionManager.isPaused -> CommandResult.error("Timeline already running")
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
        fun fromParameters(params: NavDelayMessage): DelayAction {
            val totalSeconds = if (params.delaySec != -1) {
                params.delaySec
            } else {
                (if (params.hours != -1) params.hours * 3600 else 0) +
                    (if (params.minutes != -1) params.minutes * 60 else 0) +
                    (if (params.seconds != -1) params.seconds else 0)
            }
            return DelayAction(totalSeconds * 1000L)
        }
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
    override suspend fun execute(ctx: MissionHandler): UnitResult {
        MissionActionManager.clear()

        val error = MissionActionManager.schedule(action)

        if (error != null) {
            return CommandResult.error("Error in $action: ${error.description}")
        }

        return suspendCancellableCoroutine { cont ->
            val actionKey = MissionActionManager.onFinish(action::class) {
                if (cont.isActive) {
                    cont.resume(CommandResult.ok)
                }
            }

            MissionActionManager.startListener {
                if (cont.isActive) {
                    // onError
                    cont.resume(CommandResult.error("Action failed: $it"))
                }
            }

            MissionActionManager.start()

            cont.invokeOnCancellation {
                // Add SDK cancel if available
                MissionActionManager.removeCallback(actionKey)
                MissionActionManager.stop()
            }
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
        fun fromParameters(params: DoRepositionCommandInt) = RepositionAction(
            Coordinates3D(params.latitude, params.longitude, params.altitude),
            params.speed
        )
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
        fun fromParameters(params: ConditionYawMessage): RotateAction {
            // convert to compatible intervals
            val angle = ((params.angleDeg + 180) % 360) - 180 // [0, 360] deg

            val relative = params.relative
                ?: throw IllegalArgumentException("Invalid value for param4: expected 0 or 1")

            return if (relative) {
                // clip angular speed
                RotateAction(angle, params.angularSpeedDegS.coerceIn(0f..100f), false)
            } else {
                RotateAction(angle, null, true)
            }
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
    ) {
    companion object {
        fun fromParameters(params: ImageStartCaptureMissionItem): PhotoAction =
            PhotoAction(params.totalImages, params.intervalSec.toInt())
    }
}

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
