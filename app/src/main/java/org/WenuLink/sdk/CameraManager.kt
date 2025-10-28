package org.WenuLink.sdk

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaFormat
import dji.sdk.camera.Camera
import dji.sdk.camera.VideoFeeder
import dji.sdk.codec.DJICodecManager
import io.getstream.log.taggedLogger
import java.nio.ByteBuffer
import kotlin.getValue

object CameraManager {
    private val logger by taggedLogger("CameraManager")
    private var mInstance: Camera? = null
    private var codecManager: DJICodecManager? = null
    var cameraName: String = "No Camera Connected"
        private set
    var cameraStreamID: String = "NO_CAMERA"
        private set
    var frameWidth: Int = -1
        private set
    var frameHeight: Int = -1
        private set
    var frameRate: Float = -1F
        private set

    @Synchronized
    fun init(camera: Camera)  {
        mInstance = camera
        cameraName = camera.displayName

        val resolution = camera.capabilities.videoResolutionAndFrameRateRange()[0].resolution
            .toString()
            .replace("RESOLUTION_", "")
            .split("x")
        frameWidth = resolution[0].toInt()
        frameHeight = resolution[1].toInt()

        frameRate = camera.capabilities.videoResolutionAndFrameRateRange()[0].frameRate.toString()
            .replace("FRAME_RATE_", "")
            .replace("_FPS", "")
            .replace("_DOT_", ".")
            .toFloat()

        logger.i { toString() }
    }

    fun updateStreamID(id: String) {
        logger.d { "New StreamID: $id" }
        cameraStreamID = id
    }

    @Synchronized
    fun isConnected(): Boolean {
        return mInstance != null
    }

    override fun toString(): String {
        return if (mInstance == null) {
            "No Camera to manage"
        } else
            "Managing: $cameraName $frameWidth x $frameHeight @ $frameRate"
    }

    fun startCodecWithCallback(context: Context, processYuvData: (MediaFormat, ByteBuffer?, Int, Int, Int) -> Unit) {
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
                VideoFeeder.VideoDataListener { bytes, dataSize -> // Pass the encoded data along to obtain the YUV-color data
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

    fun isCodecStarted(): Boolean {
        return codecManager != null
    }
}