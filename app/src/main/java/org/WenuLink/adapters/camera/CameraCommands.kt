package org.WenuLink.adapters.camera

import com.MAVLink.enums.CAMERA_MODE
import org.WenuLink.commands.ICommand
import org.WenuLink.sdk.CameraManager

sealed interface CameraCommand : ICommand<CameraHandler> {
    override fun validate(ctx: CameraHandler): String? = null
    override suspend fun execute(ctx: CameraHandler): String?
    override suspend fun onStop(ctx: CameraHandler) { }
}

data class SetModeCommand(val newMode: Int, val cameraIdx: Int) : CameraCommand {
    override fun validate(ctx: CameraHandler): String? = when (newMode) {
        CAMERA_MODE.CAMERA_MODE_IMAGE -> null
        CAMERA_MODE.CAMERA_MODE_VIDEO -> null
        else -> "Unrecognized mode: $newMode"
    }

    suspend fun setPhotoMode(cameraIdx: Int = 0): String? {
        if (CameraManager.isPhotoMode()) return null

        val error = CameraManager.setPhotoMode()
        if (error != null) {
            return "setPhotoMode error: $error"
        }

        return null
    }

    suspend fun setVideoMode(cameraIdx: Int = 0): String? {
        if (CameraManager.isVideoMode()) return null

        val error = CameraManager.setVideoMode()
        if (error != null) {
            return "setVideoMode error: $error"
        }

        return null
    }

    override suspend fun execute(ctx: CameraHandler): String? {
        val setModeResult = when (newMode) {
            CAMERA_MODE.CAMERA_MODE_IMAGE -> setPhotoMode()
            CAMERA_MODE.CAMERA_MODE_VIDEO -> setVideoMode()
            else -> null
        }

        if (setModeResult != null) return "Unable to set mode: $newMode"

        ctx.setMode(newMode, cameraIdx)

        return null
    }
}

data class TakePhotoCommand(val cameraIdx: Int) : CameraCommand {

    override fun validate(ctx: CameraHandler): String? = when {
        !ctx.isPhotoMode(cameraIdx) -> "Not in photo mode!"
        !ctx.captureIdle(cameraIdx) -> "Busy"
        else -> null
    }

    override suspend fun execute(ctx: CameraHandler): String? {
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
    }
}

data class StartRecordCommand(val cameraIdx: Int) : CameraCommand {

    override fun validate(ctx: CameraHandler): String? = when {
        !ctx.isVideoMode(cameraIdx) -> "Not in video mode!"
        !ctx.canRecordVideo(cameraIdx) -> "Unable to start recording"
        !ctx.captureIdle(cameraIdx) -> "Busy"
        else -> null
    }

    override suspend fun execute(ctx: CameraHandler): String? {
        // mark IN_PROGRESS
        ctx.updateCaptureStatus(CameraCaptureStatus.IN_PROGRESS, cameraIdx)
        // Trigger camera and wait for photo to be taken
        val error = CameraManager.requestStartVideoRecording()
        if (error == null) {
            ctx.captureTimestamp = System.currentTimeMillis()
        } else {
            ctx.updateCaptureStatus(CameraCaptureStatus.IDLE, cameraIdx)
        }
        return error
    }
}

data class StopRecordCommand(val cameraIdx: Int) : CameraCommand {

    override fun validate(ctx: CameraHandler): String? = when {
        !ctx.isVideoMode(cameraIdx) -> "Not in video mode!"
        !ctx.captureInProgress(cameraIdx) -> "Record not started"
        else -> null
    }

    override suspend fun execute(ctx: CameraHandler): String? {
        // Trigger camera and wait for photo to be taken
        val error = CameraManager.requestStopVideoRecording()
        if (error == null) {
            // mark IDLE back
            ctx.updateCaptureStatus(CameraCaptureStatus.IDLE, cameraIdx)
        }
        return error
    }
}
