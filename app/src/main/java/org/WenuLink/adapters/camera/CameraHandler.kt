package org.WenuLink.adapters.camera

import com.MAVLink.enums.CAMERA_CAP_FLAGS
import com.MAVLink.enums.CAMERA_MODE
import io.getstream.log.taggedLogger
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.WenuLink.commands.CommandHandler
import org.WenuLink.sdk.CameraManager

class CameraHandler : CommandHandler<CameraHandler>() {
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

    private val logger by taggedLogger(CameraHandler::class.java.simpleName)
    val availableCameras: MutableList<CameraMetadata> = mutableListOf()
    var photoSeqIndex: Int = 0
    var wasInitialized = false
        private set
    val hasCameraPresent: Boolean get() = availableCameras.isNotEmpty() && wasInitialized
    private val _captureCount = MutableStateFlow(0)
    val captureCount: StateFlow<Int> = _captureCount.asStateFlow()
    private var wasStoringPhoto = false
    private var lastSeenCaptureCount: Int = 0

    override fun registerScope(scope: CoroutineScope) {
        scope.launch {
            if (initCameras()) {
                registerCameraState()
            }
            wasInitialized = true
        }
        startCommandProcessor(scope, this@CameraHandler, logger)
    }

    override fun unload() {
        CameraManager.unregisterStateCallback()
        availableCameras.clear()
        photoSeqIndex = 0
        lastSeenCaptureCount = 0
        _captureCount.value = 0
        wasStoringPhoto = false
        wasInitialized = false
        super.unload()
    }

    private fun registerCameraState() = CameraManager.registerStateCallback { state ->
        if (state.isStoringPhoto && !wasStoringPhoto) {
            increasePhotoCounter(availableCameras.first().id)
        }
        wasStoringPhoto = state.isStoringPhoto
    }

    suspend fun initCameras(): Boolean {
        if (!CameraManager.isConnected()) return false

        CameraManager.retrieveMetadata()

        // for now assumes a single camera, however this class should manage multi camera if must
        availableCameras.add(
            CameraMetadata(
                id = 1,
                streamID = "WenuLink-${CameraManager.cameraStreamID!!}",
                name = CameraManager.cameraName!!,
                fwVersion = CameraManager.fwVersion!!,
                width = CameraManager.frameWidth,
                height = CameraManager.frameHeight,
                fps = CameraManager.frameRate.roundToInt(),
                capabilities = CAMERA_CAP_FLAGS.CAMERA_CAP_FLAGS_CAPTURE_IMAGE.toLong() or
                    CAMERA_CAP_FLAGS.CAMERA_CAP_FLAGS_CAPTURE_VIDEO.toLong() or
                    CAMERA_CAP_FLAGS.CAMERA_CAP_FLAGS_HAS_VIDEO_STREAM.toLong() or
                    CAMERA_CAP_FLAGS.CAMERA_CAP_FLAGS_HAS_MODES.toLong()
            )
        )
        // set mode as a command avoid thread issues
        dispatchCommand(
            SetModeCommand(CAMERA_MODE.CAMERA_MODE_IMAGE, availableCameras.first().id)
        )

        // TODO: grab current number of photos in storage

        logger.d { "Handling ${availableCameras.size} detected camera(s)" }
        return true
    }

    fun getCamera(cameraId: Int): CameraMetadata? =
        availableCameras.firstOrNull { it.id == cameraId }

    private fun getCameraWithIndex(cameraId: Int): Pair<Int, CameraMetadata>? =
        availableCameras.withIndex().firstOrNull { it.value.id == cameraId }
            ?.let { it.index to it.value }

    fun setMode(newMode: Int, cameraId: Int) {
        val captureType = when (newMode) {
            CAMERA_MODE.CAMERA_MODE_IMAGE -> CameraCaptureType.IMAGE
            CAMERA_MODE.CAMERA_MODE_VIDEO -> CameraCaptureType.VIDEO
            else -> CameraCaptureType.UNSET
        }

        val newState = CameraState(
            newMode,
            captureType,
            CameraCaptureStatus.IDLE,
            0
        )

        getCameraWithIndex(cameraId)?.let { (idx, camera) ->
            availableCameras[idx] = camera.copy(state = newState)
        }
    }

    fun consumeCaptureEvent(): Boolean {
        val current = captureCount.value
        if (current <= lastSeenCaptureCount) return false
        lastSeenCaptureCount = current
        return true
    }

    fun updateCaptureStatus(newStatus: CameraCaptureStatus, cameraId: Int) =
        getCameraWithIndex(cameraId)?.let { (idx, camera) ->
            availableCameras[idx] = camera.copy(
                state = camera.state.copy(captureStatus = newStatus)
            )
        }

    fun checkCaptureStatus(status: CameraCaptureStatus, cameraId: Int) =
        getCamera(cameraId)?.state?.captureStatus == status

    fun captureInProgress(cameraId: Int): Boolean = checkCaptureStatus(
        CameraCaptureStatus.IN_PROGRESS,
        cameraId
    )

    fun captureIdle(cameraId: Int): Boolean = checkCaptureStatus(
        CameraCaptureStatus.IDLE,
        cameraId
    )

    fun checkCameraMode(mode: Int, cameraId: Int) = getCamera(cameraId)?.state?.mavlinkMode == mode

    fun isPhotoMode(cameraId: Int): Boolean = checkCameraMode(
        CAMERA_MODE.CAMERA_MODE_IMAGE,
        cameraId
    )

    fun isVideoMode(cameraId: Int): Boolean = checkCameraMode(
        CAMERA_MODE.CAMERA_MODE_VIDEO,
        cameraId
    )

    fun canTakePhotoInVideo(cameraId: Int): Boolean = CameraManager.canTakePhotoInVideo()

    fun updateCaptureTimestamp(timestamp: Long?, cameraId: Int) =
        getCameraWithIndex(cameraId)?.let { (idx, camera) ->
            logger.d { "updateTimestamp" }
            availableCameras[idx] = camera.copy(
                state = camera.state.copy(captureTimestamp = timestamp)
            )
        }

    fun increasePhotoCounter(cameraId: Int) {
        logger.d { "Photo taken, increasing number" }
        photoSeqIndex += 1 // sequence index
        _captureCount.value += 1 // session count
        getCameraWithIndex(cameraId)?.let { (idx, camera) ->
            availableCameras[idx] = camera.copy(
                state = camera.state.copy(totalPhotos = camera.state.totalPhotos + 1) // total count
            )
        }
    }
}
