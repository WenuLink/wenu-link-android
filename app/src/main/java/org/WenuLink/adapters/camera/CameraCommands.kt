package org.WenuLink.adapters.camera

import com.MAVLink.enums.CAMERA_MODE
import dji.common.camera.SettingsDefinitions
import org.WenuLink.commands.CommandResult
import org.WenuLink.commands.ICommand
import org.WenuLink.commands.UnitResult
import org.WenuLink.sdk.CameraManager

sealed interface CameraCommand : ICommand<CameraHandler> {
    override fun validate(ctx: CameraHandler): UnitResult = CommandResult.ok
    override suspend fun execute(ctx: CameraHandler): UnitResult
    override suspend fun onStop(ctx: CameraHandler) { }
}

data class SetModeCommand(val newMode: Int, val cameraId: Int) : CameraCommand {
    override fun validate(ctx: CameraHandler): UnitResult = when (newMode) {
        CAMERA_MODE.CAMERA_MODE_IMAGE -> CommandResult.ok
        CAMERA_MODE.CAMERA_MODE_VIDEO -> CommandResult.ok
        else -> CommandResult.error("Unrecognized mode: $newMode")
    }

    suspend fun setPhotoMode(cameraId: Int): UnitResult {
        if (!CameraManager.isPhotoMode()) {
            CameraManager.setPhotoMode()?.let {
                return CommandResult.error("setPhotoMode error: $it")
            }
        }

        return CommandResult.ok
    }

    suspend fun setVideoMode(cameraId: Int): UnitResult {
        if (!CameraManager.isVideoMode()) {
            CameraManager.setVideoMode()?.let {
                return CommandResult.error("setVideoMode error: $it")
            }
        }

        return CommandResult.ok
    }

    override suspend fun execute(ctx: CameraHandler): UnitResult {
        val setModeResult = when (newMode) {
            CAMERA_MODE.CAMERA_MODE_IMAGE -> setPhotoMode(cameraId)
            CAMERA_MODE.CAMERA_MODE_VIDEO -> setVideoMode(cameraId)
            else -> CommandResult.ok
        }

        if (setModeResult.isOk) {
            ctx.setMode(newMode, cameraId)
        }
        return setModeResult
    }
}

data class TakePhotoCommand(val cameraId: Int) : CameraCommand {
    override fun validate(ctx: CameraHandler): UnitResult = when {
        ctx.isVideoMode(cameraId) && ctx.canTakePhotoInVideo(cameraId) -> CommandResult.ok
        !ctx.isPhotoMode(cameraId) -> CommandResult.error("Not in photo mode!")
        !ctx.captureIdle(cameraId) -> CommandResult.error("Busy")
        else -> CommandResult.ok
    }

    override suspend fun execute(ctx: CameraHandler): UnitResult {
        // mark IN_PROGRESS
        ctx.updateCaptureStatus(CameraCaptureStatus.IN_PROGRESS, cameraId)
        // Trigger camera and wait for photo to be taken
        val error = CameraManager.requestPhotoShoot()
        if (error == null) {
            ctx.updateCaptureTimestamp(System.currentTimeMillis(), cameraId)
        }
        // mark IDLE back
        ctx.updateCaptureStatus(CameraCaptureStatus.IDLE, cameraId)
        return error
            ?.let { CommandResult.error(it) }
            ?: CommandResult.ok
    }
}

data class StartRecordCommand(val cameraId: Int) : CameraCommand {
    override fun validate(ctx: CameraHandler): UnitResult = when {
        !ctx.isVideoMode(cameraId) -> CommandResult.error("Not in video mode!")
        !ctx.captureIdle(cameraId) -> CommandResult.error("Busy")
        else -> CommandResult.ok
    }

    override suspend fun execute(ctx: CameraHandler): UnitResult {
        // mark IN_PROGRESS
        ctx.updateCaptureStatus(CameraCaptureStatus.IN_PROGRESS, cameraId)
        // Trigger camera and wait for photo to be taken
        return CameraManager.requestStartVideoRecording()
            ?.let {
                ctx.updateCaptureStatus(CameraCaptureStatus.IDLE, cameraId)
                CommandResult.error(it)
            }
            ?: let {
                ctx.updateCaptureTimestamp(System.currentTimeMillis(), cameraId)
                CommandResult.ok
            }
    }
}

data class StopRecordCommand(val cameraId: Int) : CameraCommand {
    override fun validate(ctx: CameraHandler): UnitResult = when {
        !ctx.isVideoMode(cameraId) -> CommandResult.error("Not in video mode!")
        !ctx.captureInProgress(cameraId) -> CommandResult.error("Record not started")
        else -> CommandResult.ok
    }

    override suspend fun execute(ctx: CameraHandler): UnitResult {
        // Trigger camera and wait for photo to be taken
        return CameraManager.requestStopVideoRecording()
            ?.let { CommandResult.error(it) }
            ?: let {
                // mark IDLE back
                ctx.updateCaptureStatus(CameraCaptureStatus.IDLE, cameraId)
                ctx.updateCaptureTimestamp(null, cameraId)
                CommandResult.ok
            }
    }
}

data class StartIntervalShootCommand(val cameraId: Int, val intervalSeconds: Int) : CameraCommand {
    override fun validate(ctx: CameraHandler): UnitResult = when {
        !ctx.isPhotoMode(cameraId) -> CommandResult.error("Not in photo mode!")
        !ctx.captureIdle(cameraId) -> CommandResult.error("Busy")
        else -> CommandResult.ok
    }

    override suspend fun execute(ctx: CameraHandler): UnitResult {
        CameraManager.setCaptureMode(SettingsDefinitions.ShootPhotoMode.INTERVAL)
            ?.let { return CommandResult.error(it) }

        CameraManager.setIntervalSeconds(intervalSeconds)
            ?.let { return CommandResult.error(it) }

        ctx.updateCaptureStatus(CameraCaptureStatus.INTERVAL_PROGRESS, cameraId)

        return CameraManager.requestStartIntervalShoot()
            ?.let { CommandResult.error(it) }
            ?: CommandResult.ok
    }

    override suspend fun onStop(ctx: CameraHandler) {
        CameraManager.requestStopIntervalShoot()
        ctx.updateCaptureStatus(CameraCaptureStatus.IDLE, cameraId)
    }
}

data class StopIntervalShootCommand(val cameraId: Int) : CameraCommand {
    override fun validate(ctx: CameraHandler): UnitResult = when {
        !ctx.isPhotoMode(cameraId) -> CommandResult.error("Not in photo mode!")

        !ctx.checkCaptureStatus(CameraCaptureStatus.INTERVAL_PROGRESS, cameraId) ->
            CommandResult.error("Interval shoot not started")

        else -> CommandResult.ok
    }

    override suspend fun execute(ctx: CameraHandler): UnitResult =
        CameraManager.requestStopIntervalShoot()
            ?.let { CommandResult.error(it) }
            ?: let {
                ctx.updateCaptureStatus(CameraCaptureStatus.IDLE, cameraId)
                CommandResult.ok
            }
}
