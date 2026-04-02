package org.WenuLink.adapters.camera

import com.MAVLink.enums.CAMERA_MODE
import org.WenuLink.sdk.CameraManager

sealed interface CameraCommand {
    suspend fun validate(ctx: CameraHandler): String? = null
    suspend fun execute(ctx: CameraHandler, onResult: (String?) -> Unit)
}

data class SetModeCommand(val newMode: Int, val cameraIdx: Int) : CameraCommand {
    override suspend fun validate(ctx: CameraHandler): String? = null

    suspend fun setPhotoMode(cameraIdx: Int = 0): Result<String?> {
        if (!CameraManager.isPhotoMode()) {
            val error = CameraManager.setPhotoMode()
            if (error != null) {
                return Result.failure(IllegalStateException("setPhotoMode error: $error"))
            }
        }

        return Result.success(null)
    }

    suspend fun setVideoMode(cameraIdx: Int = 0): Result<String?> {
        if (!CameraManager.isVideoMode()) {
            val error = CameraManager.setVideoMode()
            if (error != null) {
                return Result.failure(IllegalStateException("setVideoMode error: $error"))
            }
        }

        return Result.success(null)
    }

    override suspend fun execute(ctx: CameraHandler, onResult: (String?) -> Unit) {
        val modeFn = when (newMode) {
            CAMERA_MODE.CAMERA_MODE_IMAGE -> ::setPhotoMode
            CAMERA_MODE.CAMERA_MODE_VIDEO -> ::setVideoMode
            else -> null
        }

        if (modeFn == null) {
            onResult("Invalid mode: $newMode")
            return
        }

        modeFn(cameraIdx)
            .onSuccess {
                ctx.setMode(newMode, cameraIdx)
                onResult(null)
            }
            .onFailure { error ->
                onResult("Invalid transition: $error")
            }
    }
}

data class TakePhotoCommand(val cameraIdx: Int) : CameraCommand {
    override suspend fun validate(ctx: CameraHandler): String? = when {
        !ctx.isPhotoMode(cameraIdx) -> "Not in photo mode!"
        !ctx.captureIdle(cameraIdx) -> "Busy"
        else -> null
    }

    override suspend fun execute(ctx: CameraHandler, onResult: (String?) -> Unit) {
        // mark IN_PROGRESS
        ctx.updateCaptureStatus(CameraCaptureStatus.IN_PROGRESS, cameraIdx)
        // Trigger camera and wait for photo to be taken
        val error = CameraManager.requestPhotoShoot()
        if (error == null) {
            ctx.captureTimestamp = System.currentTimeMillis()
            ctx.sequenceIndex += 1
        }
        onResult(error)
        // mark IDLE back
        ctx.updateCaptureStatus(CameraCaptureStatus.IDLE, cameraIdx)
    }
}

data class StartRecordCommand(val cameraIdx: Int) : CameraCommand {
    override suspend fun validate(ctx: CameraHandler): String? = when {
        !ctx.isVideoMode(cameraIdx) -> "Not in video mode!"
        !ctx.canRecordVideo(cameraIdx) -> "Unable to start recording"
        !ctx.captureIdle(cameraIdx) -> "Busy"
        else -> null
    }

    override suspend fun execute(ctx: CameraHandler, onResult: (String?) -> Unit) {
        // mark IN_PROGRESS
        ctx.updateCaptureStatus(CameraCaptureStatus.IN_PROGRESS, cameraIdx)
        // Trigger camera and wait for photo to be taken
        val error = CameraManager.requestStartVideoRecording()
        if (error == null) {
            ctx.captureTimestamp = System.currentTimeMillis()
        } else {
            ctx.updateCaptureStatus(CameraCaptureStatus.IDLE, cameraIdx)
        }
        onResult(error)
    }
}

data class StopRecordCommand(val cameraIdx: Int) : CameraCommand {
    override suspend fun validate(ctx: CameraHandler): String? = when {
        !ctx.isVideoMode(cameraIdx) -> "Not in video mode!"
        !ctx.captureInProgress(cameraIdx) -> "Record not started"
        else -> null
    }

    override suspend fun execute(ctx: CameraHandler, onResult: (String?) -> Unit) {
        // Trigger camera and wait for photo to be taken
        val error = CameraManager.requestStopVideoRecording()
        if (error == null) {
            // mark IDLE back
            ctx.updateCaptureStatus(CameraCaptureStatus.IDLE, cameraIdx)
        }
        onResult(error)
    }
}
