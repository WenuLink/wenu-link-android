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
import org.WenuLink.mavlink.messages.ImageStartCaptureMessage
import org.WenuLink.mavlink.messages.NavDelayMessage
import org.WenuLink.sdk.MissionActionManager
import org.WenuLink.sdk.MissionManager

sealed interface MissionCommand : ICommand<MissionHandler> {
    override fun validate(ctx: MissionHandler): UnitResult
    override suspend fun execute(ctx: MissionHandler): UnitResult
    override suspend fun onStop(ctx: MissionHandler)
}

data class UploadWaypointMission(val maxTries: Int = 3) : MissionCommand {
    override fun validate(ctx: MissionHandler): UnitResult = when {
        !ctx.state.uploadReady -> CommandResult.error("No mission found")
        !ctx.state.hasAssembler -> CommandResult.error("No mission assembler")
        !ctx.state.canUploadMission -> CommandResult.error("Not ready to upload")
        else -> CommandResult.ok
    }

    suspend fun tryUpload(ctx: MissionHandler): UnitResult = suspendCancellableCoroutine { cont ->
        ctx.state.assembler?.let {
            ctx.setUploadingMission(true)
            MissionManager.uploadMission(it.build(), ctx.flightSpeed) { _, error ->
                ctx.setUploadingMission(false)
                ctx.syncState()
                cont.resume(if (error == null) CommandResult.ok else CommandResult.error(error))
            }
        }

        cont.invokeOnCancellation {
            // Add SDK cancel if available
            ctx.setUploadingMission(false)
        }
    }

    override suspend fun execute(ctx: MissionHandler): UnitResult {
        val maxAttempts = maxTries.coerceAtLeast(1)
        var attempt = 0
        var result: UnitResult = CommandResult.error("No upload attempted")
        while (result.hasError && attempt < maxAttempts) {
            result = tryUpload(ctx)
            attempt++
        }
        return result
    }

    override suspend fun onStop(ctx: MissionHandler) {
        // TODO: SDK mission cancelUploading
        MissionManager.clearMission()
        ctx.setUploadingMission(false)
    }
}

data object StartWaypointMission : MissionCommand {
    override fun validate(ctx: MissionHandler): UnitResult = when {
        ctx.state.canUploadMission -> CommandResult.error("No mission found")
        ctx.state.isActive -> CommandResult.error("Already started")
        ctx.state.canStartMission -> CommandResult.ok
        else -> CommandResult.error("Unable to start")
    }

    override suspend fun execute(ctx: MissionHandler): UnitResult {
        val startResult = suspendCancellableCoroutine { cont ->
            MissionManager.startMission { error ->
                cont.resume(if (error == null) CommandResult.ok else CommandResult.error(error))
            }

            cont.invokeOnCancellation {
            }
        }
        if (startResult.hasError) return startResult

        val isActive = ctx.waitMissionStart(15_000L)
        return if (isActive) CommandResult.ok else CommandResult.error("Start mission timeout")
    }

    override suspend fun onStop(ctx: MissionHandler) = ctx.dispatchCommand(StopWaypointMission)
}

data object PauseWaypointMission : MissionCommand {
    override fun validate(ctx: MissionHandler): UnitResult = when {
        ctx.state.isActive -> CommandResult.ok
        else -> CommandResult.error("Not started")
    }

    override suspend fun execute(ctx: MissionHandler): UnitResult {
        val pauseResult = suspendCancellableCoroutine { cont ->
            MissionManager.pauseMission { error ->
                cont.resume(if (error == null) CommandResult.ok else CommandResult.error(error))
            }

            cont.invokeOnCancellation {
            }
        }
        if (pauseResult.hasError) return pauseResult

        val isPaused = ctx.waitMissionPause(15_000L)
        return if (isPaused) CommandResult.ok else CommandResult.error("Pause mission timeout")
    }

    override suspend fun onStop(ctx: MissionHandler) { }
}

data object ResumeWaypointMission : MissionCommand {
    override fun validate(ctx: MissionHandler): UnitResult = when {
        ctx.state.isPaused -> CommandResult.ok
        else -> CommandResult.error("Already in execution")
    }

    override suspend fun execute(ctx: MissionHandler): UnitResult {
        val resumeResult = suspendCancellableCoroutine { cont ->
            MissionManager.resumeMission { error ->
                cont.resume(if (error == null) CommandResult.ok else CommandResult.error(error))
            }

            cont.invokeOnCancellation {
            }
        }
        if (resumeResult.hasError) return resumeResult

        val isResumed = ctx.waitMissionStart(15_000L)
        return if (isResumed) CommandResult.ok else CommandResult.error("Resume mission timeout")
    }

    override suspend fun onStop(ctx: MissionHandler) { }
}

data object StopWaypointMission : MissionCommand {
    override fun validate(ctx: MissionHandler): UnitResult = when {
        !ctx.state.canUploadMission -> CommandResult.ok
        else -> CommandResult.error("Nothing to stop")
    }

    override suspend fun execute(ctx: MissionHandler): UnitResult {
        val stopResult = suspendCancellableCoroutine { cont ->
            MissionManager.stopMission { error ->
                cont.resume(if (error == null) CommandResult.ok else CommandResult.error(error))
            }

            cont.invokeOnCancellation {
            }
        }
        if (stopResult.hasError) return stopResult

        val isStop = ctx.waitMissionComplete(5000L)
        return if (isStop) CommandResult.ok else CommandResult.error("Stop mission timeout")
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
        !MissionActionManager.isPaused -> CommandResult.error("Timeline not paused")
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

    override suspend fun onStop(ctx: MissionHandler) = ctx.stopAction("Stop invoked")
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
        ctx.state.isActive -> CommandResult.error("Busy")
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
        // schedule and register termination
        val scheduleResult = ctx.scheduleImmediateAction(action)
        if (scheduleResult.hasError) return scheduleResult

        return suspendCancellableCoroutine { cont ->
            ctx.onActionFinish(action) {
                cont.resume(CommandResult.ok)
            }

            ctx.performAction { error ->
                cont.resume(CommandResult.error(error))
            }

            cont.invokeOnCancellation {
                ctx.stopAction("Cancellation invoked")
            }
        }
    }
}

data class RepositionAction(private val target: Coordinates3D, private val speed: Float) :
    ActionCommand(
        GoToAction(
            LocationCoordinate2D(target.lat, target.long),
            target.alt
        ).apply { flightSpeed = speed } // value should be in the range [2, 15]
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
        fun fromParameters(params: ImageStartCaptureMessage): PhotoAction =
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
