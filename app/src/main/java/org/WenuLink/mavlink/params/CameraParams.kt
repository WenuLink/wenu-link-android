package org.WenuLink.mavlink.params

import com.MAVLink.common.msg_command_long
import com.MAVLink.common.msg_mission_item_int

/**
 * Parameter bindings for
 * [MAV_CMD_REQUEST_CAMERA_INFORMATION](https://mavlink.io/en/messages/common.html#MAV_CMD_REQUEST_CAMERA_INFORMATION)
 * @param capabilities  Request camera capabilities (MAV_BOOL_TRUE). Values not equal to 0 or 1 are
 *                      invalid.
 */
data class RequestCameraInformationParams(val capabilities: Boolean?) {
    companion object {
        fun from(msg: msg_command_long) = RequestCameraInformationParams(
            capabilities = ParamUtils.toBoolean(msg.param1)
        )
    }
}

/**
 * Parameter bindings for
 * [MAV_CMD_SET_CAMERA_MODE](https://mavlink.io/en/messages/common.html#MAV_CMD_SET_CAMERA_MODE)
 * @param id    Target camera ID. 7 to 255: MAVLink camera component id. 1 to 6 for cameras attached
 *              to the autopilot, which don't have a distinct component id. 0: all cameras. This is
 *              used to target specific autopilot-connected cameras. It is also used to target
 *              specific cameras when the MAV_CMD is used in a mission.
 * @param mode  Camera mode ([com.MAVLink.enums.CAMERA_MODE])
 */
data class SetCameraModeParams(val id: Int, val mode: Int) {
    companion object {
        fun from(msg: msg_command_long) = SetCameraModeParams(
            id = msg.param1.toInt(),
            mode = msg.param2.toInt()
        )
    }
}

/**
 * Parameter bindings for
 * [MAV_CMD_IMAGE_START_CAPTURE](https://mavlink.io/en/messages/common.html#MAV_CMD_IMAGE_START_CAPTURE)
 * @param targetCameraId    Target camera ID. 7 to 255: MAVLink camera component id. 1 to 6 for
 *                          cameras attached to the autopilot, which don't have a distinct component
 *                          id. 0: all cameras. This is used to target specific autopilot-connected
 *                          cameras. It is also used to target specific cameras when the MAV_CMD is
 *                          used in a mission.
 * @param intervalSec       Desired elapsed time between two consecutive pictures (in seconds).
 *                          Minimum values depend on hardware (typically greater than 2 seconds).
 * @param totalImages       Total number of images to capture. 0 to capture forever/until
 *                          MAV_CMD_IMAGE_STOP_CAPTURE.
 * @param sequenceNumber    Capture sequence number starting from 1. This is only valid for
 *                          single-capture (param3 == 1), otherwise set to 0. Increment the capture
 *                          ID for each capture command to prevent double captures when a command is
 *                          re-transmitted.
 */
data class ImageStartCaptureParams(
    val targetCameraId: Int,
    val intervalSec: Float,
    val totalImages: Int,
    val sequenceNumber: Int
) {
    companion object {
        fun from(msg: msg_command_long) = ImageStartCaptureParams(
            targetCameraId = msg.param1.toInt(),
            intervalSec = msg.param2,
            totalImages = msg.param3.toInt(),
            sequenceNumber = msg.param4.toInt()
        )

        fun from(msg: msg_mission_item_int) = ImageStartCaptureParams(
            targetCameraId = msg.param1.toInt(),
            intervalSec = msg.param2,
            totalImages = msg.param3.toInt(),
            sequenceNumber = msg.param4.toInt()
        )
    }
}

/**
 * Parameter bindings for
 * [MAV_CMD_IMAGE_STOP_CAPTURE](https://mavlink.io/en/messages/common.html#MAV_CMD_IMAGE_STOP_CAPTURE).
 *
 * @param targetCameraId    Target camera ID. 7 to 255: MAVLink camera component id. 1 to 6 for
 *                          cameras attached to the autopilot, which don't have a distinct component
 *                          id. 0: all cameras. This is used to target specific autopilot-connected
 *                          cameras. It is also used to target specific cameras when the MAV_CMD is
 *                          used in a mission.
 */
data class ImageStopCaptureParams(val targetCameraId: Int) {
    companion object {
        fun from(msg: msg_command_long) = ImageStopCaptureParams(
            targetCameraId = msg.param1.toInt()
        )
    }
}

/**
 * Parameter bindings for
 * [MAV_CMD_VIDEO_START_CAPTURE](https://mavlink.io/en/messages/common.html#MAV_CMD_VIDEO_START_CAPTURE).
 *
 * @param streamId          Video Stream ID (0 for all streams)
 * @param statusFreqHz      Frequency CAMERA_CAPTURE_STATUS messages should be sent while recording
 *                          (0 for no messages, otherwise frequency)
 * @param targetCameraId    Target camera ID. 7 to 255: MAVLink camera component id. 1 to 6 for
 *                          cameras attached to the autopilot, which don't have a distinct component
 *                          id. 0: all cameras. This is used to target specific autopilot-connected
 *                          cameras. It is also used to target specific cameras when the MAV_CMD is
 *                          used in a mission.
 */
data class VideoStartCaptureParams(
    val streamId: Int,
    val statusFreqHz: Float,
    val targetCameraId: Int
) {
    companion object {
        fun from(msg: msg_command_long) = VideoStartCaptureParams(
            streamId = msg.param1.toInt(),
            statusFreqHz = msg.param2,
            targetCameraId = msg.param3.toInt()
        )
    }
}

/**
 * Parameter bindings for
 * [MAV_CMD_VIDEO_STOP_CAPTURE](https://mavlink.io/en/messages/common.html#MAV_CMD_VIDEO_STOP_CAPTURE).
 *
 * @param streamId          Video Stream ID (0 for all streams)
 * @param targetCameraId    Target camera ID. 7 to 255: MAVLink camera component id. 1 to 6 for
 *                          cameras attached to the autopilot, which don't have a distinct component
 *                          id. 0: all cameras. This is used to target specific autopilot-connected
 *                          cameras. It is also used to target specific cameras when the MAV_CMD is
 *                          used in a mission.
 */
data class VideoStopCaptureParams(val streamId: Int, val targetCameraId: Int) {
    companion object {
        fun from(msg: msg_command_long) = VideoStopCaptureParams(
            streamId = msg.param1.toInt(),
            targetCameraId = msg.param2.toInt()
        )
    }
}
