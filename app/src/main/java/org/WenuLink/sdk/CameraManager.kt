package org.WenuLink.sdk

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaFormat
import dji.common.camera.SettingsDefinitions
import dji.common.camera.SystemState
import dji.common.error.DJIError
import dji.common.util.CommonCallbacks
import dji.sdk.camera.Camera
import dji.sdk.camera.VideoFeeder
import dji.sdk.codec.DJICodecManager
import io.getstream.log.taggedLogger
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import org.WenuLink.adapters.AsyncUtils

/**
 * https://developer.dji.com/api-reference/android-api/Components/Camera/DJICamera.html
 */
object CameraManager {
    private val logger by taggedLogger(CameraManager::class.java.simpleName)
    private var mInstance: Camera? = null
    private var codecManager: DJICodecManager? = null
    var cameraName: String? = null
        private set
    var cameraStreamID: String? = null
        private set
    var frameWidth = -1
        private set
    var frameHeight = -1
        private set
    var frameRate = -1f
        private set
    var serialNumber: String? = null
        private set
    var fwVersion: String? = null
    var cameraMode = SettingsDefinitions.CameraMode.UNKNOWN
    var captureMode = SettingsDefinitions.ShootPhotoMode.UNKNOWN

    @Synchronized
    fun init(camera: Camera) {
        mInstance = camera
        cameraName = camera.displayName

        val resolution = camera.capabilities.videoResolutionAndFrameRateRange()[0].resolution
            .toString()
            .replace("RESOLUTION_", "")
            .split("x")
        frameWidth = resolution[0].toInt()
        frameHeight = resolution[1].toInt()

        frameRate = camera.capabilities.videoResolutionAndFrameRateRange()[0].frameRate
            .toString()
            .replace("FRAME_RATE_", "")
            .replace("_FPS", "")
            .replace("_DOT_", ".")
            .toFloat()

        logger.i { toString() }
    }

    suspend fun retrieveCameraMode(): SettingsDefinitions.CameraMode? =
        suspendCancellableCoroutine { cont ->
            mInstance?.getMode(
                object : CommonCallbacks.CompletionCallbackWith<SettingsDefinitions.CameraMode> {
                    override fun onSuccess(mode: SettingsDefinitions.CameraMode?) =
                        cont.resume(mode)

                    override fun onFailure(error: DJIError?) = cont.resume(null)
                }
            ) ?: cont.resume(null)
        }

    suspend fun retrieveCaptureMode(): SettingsDefinitions.ShootPhotoMode? =
        suspendCancellableCoroutine { cont ->
            mInstance?.getShootPhotoMode(
                object :
                    CommonCallbacks.CompletionCallbackWith<SettingsDefinitions.ShootPhotoMode> {
                    override fun onSuccess(mode: SettingsDefinitions.ShootPhotoMode?) =
                        cont.resume(mode)

                    override fun onFailure(error: DJIError?) {
                        if (error != null) {
                            logger.d { "Error in reading CameraMode: ${error.description}" }
                        }
                        cont.resume(null)
                    }
                }
            ) ?: cont.resume(null)
        }

    suspend fun retrieveMetadata() = mInstance?.let { camInstance ->
        fwVersion = SDKUtils.retrieveFirmwareVersion(camInstance)
        serialNumber = SDKUtils.retrieveSerialNumber(camInstance)
        retrieveCameraMode()?.let { this.cameraMode = it }
        retrieveCaptureMode()?.let { this.captureMode = it }
    }

    fun updateStreamID(id: String) {
        logger.d { "New StreamID: $id" }
        cameraStreamID = id
    }

    @Synchronized
    fun isConnected() = mInstance != null

    override fun toString(): String = if (isConnected()) {
        "Managing: $cameraName $frameWidth x $frameHeight @ $frameRate"
    } else {
        "No Camera to manage"
    }

    fun startCodecWithCallback(
        context: Context,
        processYuvData: (MediaFormat, ByteBuffer?, Int, Int, Int) -> Unit
    ) {
        if (isCodecStarted()) {
            logger.d { "codecManager not null" }
            return
        }
        logger.d { "Starting codec" }

        // Pass SurfaceTexture as null to force the Yuv callback - width and height for the surface texture does not matter
        codecManager = DJICodecManager(context, null as SurfaceTexture?, 0, 0)
        codecManager!!.enabledYuvData(true)
        codecManager!!.yuvDataCallback =
            DJICodecManager.YuvDataCallback { mediaFormat, videoBuffer, dataSize, width, height ->
                processYuvData(mediaFormat, videoBuffer, dataSize, width, height)
            }

        // Could create more cases if other drones from DJI require a different approach
        if (AircraftManager.getModelName() == "DJI Air 2S") {
            // The Air 2S relies on the VideoDataListener to obtain the video feed
            // The onReceive callback provides us the raw H264 (at least according to official documentation). To decode it we send it to our DJICodecManager
            // H264 or H265 encoding is done to compress and save bandwidth. (4K video might force a switch to H265 on DJI drones)
            val videoDataListener: VideoFeeder.VideoDataListener =
                VideoFeeder.VideoDataListener { bytes, dataSize ->
                    // Pass the encoded data along to obtain the YUV-color data
                    codecManager!!.sendDataToDecoder(bytes, dataSize)
                }
            VideoFeeder.getInstance().getPrimaryVideoFeed().addVideoDataListener(videoDataListener)
        }
    }

    fun stopCodec() {
        if (!isCodecStarted()) {
            logger.d { "no codecManager found" }
            return
        }
        codecManager!!.enabledYuvData(false)
        codecManager!!.yuvDataCallback = null
        codecManager!!.destroyCodec()
        codecManager = null
    }

    fun isCodecStarted() = codecManager != null

    fun registerStateCallback(callback: (SystemState) -> Unit) =
        mInstance?.setSystemStateCallback(callback)

    fun unregisterStateCallback() = mInstance?.setSystemStateCallback(null)

    private suspend fun setCameraMode(mode: SettingsDefinitions.CameraMode): String? =
        suspendCancellableCoroutine { cont ->
            if (cameraMode == mode) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }

            mInstance?.setMode(
                mode,
                SDKUtils.createCompletionCallback { error ->

                    if (error == null) {
                        this@CameraManager.cameraMode = mode
                    }

                    if (cont.isActive) {
                        cont.resume(error)
                    }
                }
            ) ?: cont.resume("Camera instance is null")
        }

    suspend fun setPhotoMode(): String? = setCameraMode(SettingsDefinitions.CameraMode.SHOOT_PHOTO)

    suspend fun setVideoMode(): String? = setCameraMode(SettingsDefinitions.CameraMode.RECORD_VIDEO)

    suspend fun setBroadcastMode(): String? =
        setCameraMode(SettingsDefinitions.CameraMode.BROADCAST)

    suspend fun setCaptureMode(mode: SettingsDefinitions.ShootPhotoMode): String? =
        suspendCancellableCoroutine { cont ->

            if (captureMode == mode) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }

            mInstance?.setShootPhotoMode(
                mode,
                SDKUtils.createCompletionCallback { error ->

                    if (error == null) {
                        this@CameraManager.captureMode = mode
                    }

                    if (cont.isActive) {
                        cont.resume(error)
                    }
                }
            )

            cont.invokeOnCancellation {
                // Optional: add SDK cancel logic if available
            }
        }

    suspend fun setSingleShoot(): String? =
        setCaptureMode(SettingsDefinitions.ShootPhotoMode.SINGLE)

    fun isPhotoMode() = cameraMode == SettingsDefinitions.CameraMode.SHOOT_PHOTO

    fun isVideoMode() = cameraMode == SettingsDefinitions.CameraMode.RECORD_VIDEO

    fun isSingleShoot() = captureMode == SettingsDefinitions.ShootPhotoMode.SINGLE

    fun canRecordVideo() = mInstance?.isCaptureInVideoSupported == true

    suspend fun requestPhotoShoot(): String? {
        val camera = mInstance ?: return "Camera instance is null"

        // Ensure for ShootPhotoMode.SINGLE
        if (!isSingleShoot()) {
            val error = setSingleShoot()
            if (error != null) {
                return error
            }

            val success = AsyncUtils.waitTimeout(isReady = ::isSingleShoot)
            if (!success) {
                return "Failed to switch to SINGLE mode"
            }
        }

        // Launch capture
        return suspendCancellableCoroutine { cont ->
            camera.startShootPhoto { error ->
                cont.resume(error?.description)
            }

            cont.invokeOnCancellation {
                // Add SDK cancel if available
            }
        }
    }
    suspend fun requestStartVideoRecording(): String? {
        val camera = mInstance ?: return "No camera instance"

        // Launch capture
        return suspendCancellableCoroutine { cont ->
            camera.startRecordVideo { error ->
                cont.resume(error?.description)
            }

            cont.invokeOnCancellation {
                // Add SDK cancel if available
            }
        }
    }

    suspend fun requestStopVideoRecording(): String? {
        val camera = mInstance ?: return "No camera instance"

        // stop capture
        return suspendCancellableCoroutine { cont ->
            camera.stopRecordVideo(
                SDKUtils.createCompletionCallback(cont::resume)
            )

            cont.invokeOnCancellation {
                // Add SDK cancel if available
            }
        }
    }

    suspend fun setIntervalSeconds(seconds: Int): String? = suspendCancellableCoroutine { cont ->
        mInstance?.setPhotoTimeIntervalSettings(
            SettingsDefinitions.PhotoTimeIntervalSettings(255, seconds),
            SDKUtils.createCompletionCallback { error ->
                if (cont.isActive) cont.resume(error)
            }
        ) ?: cont.resume("Camera instance is null")
    }

    suspend fun requestStartIntervalShoot(): String? {
        val camera = mInstance ?: return "Camera instance is null"
        return suspendCancellableCoroutine { cont ->
            camera.startShootPhoto(
                SDKUtils.createCompletionCallback { error ->
                    if (cont.isActive) cont.resume(error)
                }
            )
        }
    }

    suspend fun requestStopIntervalShoot(): String? {
        val camera = mInstance ?: return "Camera instance is null"
        return suspendCancellableCoroutine { cont ->
            camera.stopShootPhoto(
                SDKUtils.createCompletionCallback { error ->
                    if (cont.isActive) cont.resume(error)
                }
            )
        }
    }
}
