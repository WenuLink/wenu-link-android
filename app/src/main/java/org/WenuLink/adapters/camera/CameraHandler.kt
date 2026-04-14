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
    var captureTimestamp: Long = System.currentTimeMillis()
    var wasInitialized = false
        private set
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
            _captureCount.value += 1
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
        setMode(CAMERA_MODE.CAMERA_MODE_IMAGE, 0)

        logger.d { "Managing ${availableCameras.size} detected camera(s)" }
        return true
    }

    fun getCamera(cameraId: Int): CameraMetadata? =
        availableCameras.firstOrNull { it.id == cameraId }

    fun setMode(newMode: Int, cameraIdx: Int = 0) {
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

        getCamera(cameraIdx)?.let {
            availableCameras.set(cameraIdx, it.copy(state = newState))
        }
    }

    fun consumeCaptureEvent(): Boolean {
        val current = captureCount.value
        if (current <= lastSeenCaptureCount) return false
        lastSeenCaptureCount = current
        photoSeqIndex += 1
        return true
    }

    fun updateCaptureStatus(newStatus: CameraCaptureStatus, cameraIdx: Int = 0) {
        getCamera(cameraIdx)?.let {
            availableCameras.set(
                cameraIdx,
                it.copy(
                    state = it.state.copy(
                        captureStatus = newStatus
                    )
                )
            )
        }
    }

    fun checkCaptureStatus(status: CameraCaptureStatus, cameraIdx: Int = 0) =
        getCamera(cameraIdx)?.state?.captureStatus == status

    fun captureInProgress(cameraIdx: Int = 0): Boolean = checkCaptureStatus(
        CameraCaptureStatus.IN_PROGRESS,
        cameraIdx
    )

    fun captureIdle(cameraIdx: Int = 0): Boolean = checkCaptureStatus(
        CameraCaptureStatus.IDLE,
        cameraIdx
    )

    fun checkCameraMode(mode: Int, cameraIdx: Int = 0) =
        getCamera(cameraIdx)?.state?.mavlinkMode == mode

    fun isPhotoMode(cameraIdx: Int = 0): Boolean = checkCameraMode(
        CAMERA_MODE.CAMERA_MODE_IMAGE,
        cameraIdx
    )

    fun isVideoMode(cameraIdx: Int = 0): Boolean = checkCameraMode(
        CAMERA_MODE.CAMERA_MODE_VIDEO,
        cameraIdx
    )

    fun canRecordVideo(cameraIdx: Int = 0): Boolean = CameraManager.canRecordVideo()
}
