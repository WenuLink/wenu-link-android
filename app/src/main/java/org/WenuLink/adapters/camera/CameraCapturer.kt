package org.WenuLink.adapters.camera

import android.content.Context
import android.media.MediaFormat
import io.getstream.log.taggedLogger
import java.nio.ByteBuffer
import kotlin.math.roundToInt
import org.WenuLink.sdk.CameraManager
import org.WenuLink.webrtc.utils.videoBuffer2VideoFrame
import org.webrtc.CapturerObserver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer

class CameraCapturer : VideoCapturer {
    data class MediaMetadata(
        val mediaStreamId: String,
        val videoCameraName: String,
        val videoResolutionWidth: Int,
        val videoResolutionHeight: Int,
        val fps: Int
    ) {
        companion object {
            fun fromCameraManager(): MediaMetadata? {
                val streamId = CameraManager.cameraStreamID ?: return null
                val cameraName = CameraManager.cameraName ?: return null
                if (CameraManager.frameWidth <= 0 ||
                    CameraManager.frameHeight <= 0 ||
                    CameraManager.frameRate <= 0
                ) {
                    return null
                }
                return MediaMetadata(
                    "WenuLink-$streamId",
                    cameraName,
                    CameraManager.frameWidth,
                    CameraManager.frameHeight,
                    CameraManager.frameRate.roundToInt()
                )
            }
        }
    }

    companion object {
        private val logger by taggedLogger(CameraCapturer::class.java.simpleName)
        fun hasCameraPresent(): Boolean = CameraManager.isConnected()
    }

    private var context: Context? = null
    private var observer: CapturerObserver? = null

    private fun processYuvData(
        mediaFormat: MediaFormat,
        videoBuffer: ByteBuffer,
        dataSize: Int,
        width: Int,
        height: Int
    ) {
        val videoFrame =
            videoBuffer2VideoFrame(mediaFormat, videoBuffer, width, height)
        if (videoFrame != null && observer != null) {
            // If frame captured and observed present, feed it
            observer!!.onFrameCaptured(videoFrame)
            videoFrame.release()
        }
    }

    override fun initialize(
        surfaceTextureHelper: SurfaceTextureHelper,
        applicationContext: Context,
        capturerObserver: CapturerObserver
    ) {
        this.context = applicationContext
        this.observer = capturerObserver
    }

    override fun startCapture(width: Int, height: Int, framerate: Int) {
        // Hook onto the DJI onYuvDataReceived event
        if (!CameraManager.isConnected()) {
            logger.e { "No camera connected" }
            return
        }
        CameraManager.startCodecWithCallback(context as Context) {
                mediaFormat,
                videoBuffer,
                dataSize,
                width,
                height
            ->
            if (videoBuffer != null) {
                this.processYuvData(
                    mediaFormat,
                    videoBuffer,
                    dataSize,
                    width,
                    height
                )
            }
        }
    }

    @Throws(InterruptedException::class)
    override fun stopCapture() {
        CameraManager.stopCodec()
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
        // Empty on purpose since no different format exists(?)
    }

    override fun dispose() {
        // Stop receiving frames on the callback from the decoder
        observer = null
    }

    override fun isScreencast(): Boolean = false
}
