package org.WenuLink.adapters.camera

import com.MAVLink.enums.CAMERA_MODE
import org.WenuLink.adapters.aircraft.TelemetryData

enum class CameraCaptureType {
    UNSET,
    IMAGE,
    VIDEO
}

enum class CameraCaptureStatus(val value: Int) {
    IDLE(0),
    IN_PROGRESS(1),
    INTERVAL_IDLE(2),
    INTERVAL_PROGRESS(3)
}

data class CameraState(
    val mavlinkMode: Int = CAMERA_MODE.CAMERA_MODE_IMAGE,
    val captureType: CameraCaptureType = CameraCaptureType.IMAGE,
    val captureStatus: CameraCaptureStatus = CameraCaptureStatus.IDLE,
    /**
     * captureTime depends on CaptureType:
     * - CaptureType.IMAGE: capture interval in seconds
     * - CaptureType.VIDEO: elapsed recording time in milliseconds
     */
    val captureTimestamp: Long? = null,
    val totalPhotos: Int = 0
) {
    val timeMillis: Long
        get() = captureTimestamp?.let { System.currentTimeMillis() - it } ?: 0
}

data class CameraMetadata(
    val id: Int = 1,
    val streamID: String,
    val name: String,
    val fwVersion: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val state: CameraState = CameraState(),
    val capabilities: Long
)

data class ImageMetadata(
    val index: Int,
    val captureOk: Boolean,
    val cameraID: Int,
    val telemetry: TelemetryData
)
