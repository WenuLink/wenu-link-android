package org.WenuLink.adapters

import com.MAVLink.enums.CAMERA_MODE
import io.getstream.log.taggedLogger
import kotlin.getValue
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.WenuLink.adapters.actions.CameraAction
import org.WenuLink.sdk.CameraManager

class CameraHandler {
    companion object {
        private var mInstance: CameraHandler? = null

        @Synchronized
        fun getInstance(): CameraHandler {
            if (mInstance == null) {
                mInstance = CameraHandler()
            }
            return mInstance!!
        }
    }
    private val logger by taggedLogger("CameraHandler")
    private val _cameraActions = MutableSharedFlow<CameraAction>(
        extraBufferCapacity = 10
    )

    val cameraActions = _cameraActions.asSharedFlow()

    private val availableCameras: MutableList<CameraMetadata> = mutableListOf()
    val list: List<CameraMetadata>
        get() = availableCameras.toList()

    suspend fun initCameras(): Boolean {
        if (!CameraManager.isConnected()) {
            return false
        }
        logger.d { "initCamera" }
        CameraManager.retrieveMetadata()
        fun hasFirmware() = CameraManager.fwVersion != null
        val hasFW = AsyncUtils.waitTimeout(intervalTime = 1000L, timeout = 60000L, ::hasFirmware)
        fun hasSerialNumber() = CameraManager.serialNumber != null
        val hasSN = AsyncUtils.waitTimeout(
            intervalTime = 1000L,
            timeout = 60000L,
            ::hasSerialNumber
        )

        // for now assumes a single camera, however this class should manage multi camera if must
        availableCameras.add(
            CameraMetadata(
                id = 1,
                streamID = "WenuLink-${CameraManager.cameraStreamID!!}",
                name = CameraManager.cameraName!!,
                fwVersion = CameraManager.fwVersion!!,
                width = CameraManager.frameWidth,
                height = CameraManager.frameHeight,
                fps = CameraManager.frameRate.roundToInt()
            )
        )
        logger.d { "availableCameras(${availableCameras.size})" }
        updateMode(CAMERA_MODE.CAMERA_MODE_IMAGE, 0)

        logger.d { "Managing ${availableCameras.size} detected camera(s)" }
        return true
    }

    private fun transitionToMode(newMode: Int, cameraIdx: Int = 0) {
        val captureType = when (newMode) {
            CAMERA_MODE.CAMERA_MODE_IMAGE -> CameraCaptureType.IMAGE
            CAMERA_MODE.CAMERA_MODE_VIDEO -> CameraCaptureType.VIDEO
            else -> CameraCaptureType.UNSET
        }

        val newState = CameraState(
            mavlinkMode = newMode,
            captureType = captureType,
            captureStatus = CameraCaptureStatus.IDLE,
            captureTime = 0
        )

        availableCameras[cameraIdx] =
            availableCameras[cameraIdx].copy(state = newState)
    }

    suspend fun setPhotoMode(cameraIdx: Int = 0): Boolean {
        if (CameraManager.isPhotoMode()) return true

        val error = CameraManager.setPhotoMode()
        logger.d { "Camera setPhotoMode error: $error" }

        return error == null
    }

    suspend fun setVideoMode(cameraIdx: Int = 0): Boolean {
        if (CameraManager.isVideoMode()) return true

        val error = CameraManager.setVideoMode()
        logger.d { "Camera setVideoMode error: $error" }

        return error == null
    }

    suspend fun updateMode(newMode: Int, cameraIdx: Int = 0): Boolean {
        val success = when (newMode) {
            CAMERA_MODE.CAMERA_MODE_IMAGE -> setPhotoMode(cameraIdx)
            CAMERA_MODE.CAMERA_MODE_VIDEO -> setVideoMode(cameraIdx)
            else -> false
        }

        if (success) {
            transitionToMode(newMode, cameraIdx)
        }

        return success
    }

    fun updateCaptureStatus(newStatus: CameraCaptureStatus, cameraIdx: Int = 0) {
        val camera = availableCameras[cameraIdx]
        availableCameras[cameraIdx] =
            camera.copy(
                state = camera.state.copy(
                    captureStatus = newStatus
                )
            )
    }

    fun checkCaptureStatus(status: CameraCaptureStatus, cameraIdx: Int = 0): Boolean =
        availableCameras[cameraIdx].state.captureStatus == status

    fun captureInProgress(cameraIdx: Int = 0): Boolean =
        checkCaptureStatus(CameraCaptureStatus.IN_PROGRESS)

    fun captureIdle(cameraIdx: Int = 0): Boolean = checkCaptureStatus(CameraCaptureStatus.IDLE)

    fun requestAction(action: CameraAction) {
        _cameraActions.tryEmit(action)
    }

    fun registerHandlerScope(scope: CoroutineScope) {
        _cameraActions
            .onEach { action ->
                handleAction(action)
            }
            .launchIn(scope)
    }

    private suspend fun handleAction(action: CameraAction) {
        when (action) {
            is CameraAction.SetMode -> {
                val error = CameraManager.setPhotoMode()
                action.onResult(error)
                if (error == null) {
                    updateMode(action.mode, action.cameraIdx)
                }
            }

            is CameraAction.TakePhoto -> {
                val error = shootPhoto(action.cameraIdx)
                action.onResult(error)
            }

            is CameraAction.StartRecord -> {
                setVideoMode()
                updateCaptureStatus(CameraCaptureStatus.IN_PROGRESS)
                // start recording here
            }

            is CameraAction.StopRecord -> {
                updateCaptureStatus(CameraCaptureStatus.IDLE)
                // stop recording here
            }

            is CameraAction.GimbalPitch -> {
                // call gimbal handler
            }
        }
    }

    suspend fun shootPhoto(cameraIdx: Int = 0): String? {
        if (!captureIdle(cameraIdx)) {
            return "Camera is busy!"
        }

        val isPhotoMode = setPhotoMode(cameraIdx)

        if (isPhotoMode) {
            // mark IN_PROGRESS
            updateCaptureStatus(CameraCaptureStatus.IN_PROGRESS, cameraIdx)
            // Trigger camera and wait for photo to be taken
            val error = CameraManager.requestPhotoShoot()
            // mark IDLE back
            updateCaptureStatus(CameraCaptureStatus.IDLE, cameraIdx)
            return error
        } else {
            return "No in PhotoMode!"
        }
    }
}
