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

data class SetModeCommand(val newMode: Int, val cameraIdx: Int) : CameraCommand {
    override fun validate(ctx: CameraHandler): UnitResult = when (newMode) {
        CAMERA_MODE.CAMERA_MODE_IMAGE -> CommandResult.ok
        CAMERA_MODE.CAMERA_MODE_VIDEO -> CommandResult.ok
        else -> CommandResult.error("Unrecognized mode: $newMode")
    }

    suspend fun setPhotoMode(cameraIdx: Int = 0): UnitResult {
        if (!CameraManager.isPhotoMode()) {
            val error = CameraManager.setPhotoMode()
            if (error != null) {
                return CommandResult.error("setPhotoMode error: $error")
            }
        }

        return CommandResult.ok
    }

    suspend fun setVideoMode(cameraIdx: Int = 0): UnitResult {
        if (!CameraManager.isVideoMode()) {
            val error = CameraManager.setVideoMode()
            if (error != null) {
                return CommandResult.error("setVideoMode error: $error")
            }
        }

        return CommandResult.ok
    }

    override suspend fun execute(ctx: CameraHandler): UnitResult {
        val setModeResult = when (newMode) {
            CAMERA_MODE.CAMERA_MODE_IMAGE -> setPhotoMode()
            CAMERA_MODE.CAMERA_MODE_VIDEO -> setVideoMode()
            else -> CommandResult.ok
        }

        if (setModeResult.isOk) {
            ctx.setMode(newMode, cameraIdx)
        }
        return setModeResult
    }
}

data class TakePhotoCommand(val cameraIdx: Int) : CameraCommand {
    override fun validate(ctx: CameraHandler): UnitResult = when {
        !ctx.isPhotoMode(cameraIdx) -> CommandResult.error("Not in photo mode!")
        !ctx.captureIdle(cameraIdx) -> CommandResult.error("Busy")
        else -> CommandResult.ok
    }

    override suspend fun execute(ctx: CameraHandler): UnitResult {
        // mark IN_PROGRESS
        ctx.updateCaptureStatus(CameraCaptureStatus.IN_PROGRESS, cameraIdx)
        // Trigger camera and wait for photo to be taken
        val error = CameraManager.requestPhotoShoot()
        if (error == null) {
            ctx.captureTimestamp = System.currentTimeMillis()
            ctx.photoSeqIndex += 1
        }
        // mark IDLE back
        ctx.updateCaptureStatus(CameraCaptureStatus.IDLE, cameraIdx)
        return error
            ?.let { CommandResult.error(it) }
            ?: CommandResult.ok
    }
}

data class StartRecordCommand(val cameraIdx: Int) : CameraCommand {
    override fun validate(ctx: CameraHandler): UnitResult = when {
        !ctx.isVideoMode(cameraIdx) -> CommandResult.error("Not in video mode!")
        !ctx.canRecordVideo(cameraIdx) -> CommandResult.error("Unable to start recording")
        !ctx.captureIdle(cameraIdx) -> CommandResult.error("Busy")
        else -> CommandResult.ok
    }

    override suspend fun execute(ctx: CameraHandler): UnitResult {
        // mark IN_PROGRESS
        ctx.updateCaptureStatus(CameraCaptureStatus.IN_PROGRESS, cameraIdx)
        // Trigger camera and wait for photo to be taken
        val error = CameraManager.requestStartVideoRecording()
        return if (error == null) {
            ctx.captureTimestamp = System.currentTimeMillis()
            CommandResult.ok
        } else {
            ctx.updateCaptureStatus(CameraCaptureStatus.IDLE, cameraIdx)
            CommandResult.error(error)
        }
    }
}

data class StopRecordCommand(val cameraIdx: Int) : CameraCommand {
    override fun validate(ctx: CameraHandler): UnitResult = when {
        !ctx.isVideoMode(cameraIdx) -> CommandResult.error("Not in video mode!")
        !ctx.captureInProgress(cameraIdx) -> CommandResult.error("Record not started")
        else -> CommandResult.ok
    }

    override suspend fun execute(ctx: CameraHandler): UnitResult {
        // Trigger camera and wait for photo to be taken
        val error = CameraManager.requestStopVideoRecording()
        return if (error == null) {
            // mark IDLE back
            ctx.updateCaptureStatus(CameraCaptureStatus.IDLE, cameraIdx)
            CommandResult.ok
        } else {
            CommandResult.error(error)
        }
    }
}

data class StartIntervalShootCommand(val cameraIdx: Int, val intervalSeconds: Int) : CameraCommand {
    override fun validate(ctx: CameraHandler): UnitResult = when {
        !ctx.isPhotoMode(cameraIdx) -> CommandResult.error("Not in photo mode!")
        !ctx.captureIdle(cameraIdx) -> CommandResult.error("Busy")
        else -> CommandResult.ok
    }

    override suspend fun execute(ctx: CameraHandler): UnitResult {
        val modeError = CameraManager.setCaptureMode(SettingsDefinitions.ShootPhotoMode.INTERVAL)
        if (modeError != null) return CommandResult.error(modeError)

        val intervalError = CameraManager.setIntervalSeconds(intervalSeconds)
        if (intervalError != null) return CommandResult.error(intervalError)

        ctx.updateCaptureStatus(CameraCaptureStatus.INTERVAL_PROGRESS, cameraIdx)

        return CameraManager.requestStartIntervalShoot()
            ?.let { CommandResult.error(it) }
            ?: CommandResult.ok
    }

    override suspend fun onStop(ctx: CameraHandler) {
        CameraManager.requestStopIntervalShoot()
        ctx.updateCaptureStatus(CameraCaptureStatus.IDLE, cameraIdx)
    }
}

data class StopIntervalShootCommand(val cameraIdx: Int) : CameraCommand {
    override fun validate(ctx: CameraHandler): UnitResult = when {
        !ctx.isPhotoMode(cameraIdx) -> CommandResult.error("Not in photo mode!")

        !ctx.checkCaptureStatus(CameraCaptureStatus.INTERVAL_PROGRESS, cameraIdx) ->
            CommandResult.error("Interval shoot not started")

        else -> CommandResult.ok
    }

    override suspend fun execute(ctx: CameraHandler): UnitResult {
        val error = CameraManager.requestStopIntervalShoot()
        return if (error == null) {
            ctx.updateCaptureStatus(CameraCaptureStatus.IDLE, cameraIdx)
            CommandResult.ok
        } else {
            CommandResult.error(error)
        }
    }
}
