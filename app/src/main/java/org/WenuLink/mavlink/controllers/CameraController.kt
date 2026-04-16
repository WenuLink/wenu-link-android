package org.WenuLink.mavlink.controllers

import com.MAVLink.common.msg_camera_capture_status
import com.MAVLink.common.msg_camera_image_captured
import com.MAVLink.common.msg_camera_information
import com.MAVLink.common.msg_camera_settings
import com.MAVLink.common.msg_command_long
import com.MAVLink.common.msg_storage_information
import com.MAVLink.enums.CAMERA_MODE
import com.MAVLink.enums.FIRMWARE_VERSION_TYPE
import com.MAVLink.enums.MAV_CMD
import com.MAVLink.enums.MAV_RESULT
import com.MAVLink.enums.STORAGE_STATUS
import com.MAVLink.enums.STORAGE_TYPE
import com.MAVLink.enums.STORAGE_USAGE_FLAG
import io.getstream.log.taggedLogger
import kotlin.math.roundToLong
import org.WenuLink.adapters.OrientationUtils
import org.WenuLink.adapters.WenuLinkCommand
import org.WenuLink.adapters.WenuLinkHandler
import org.WenuLink.adapters.aircraft.TelemetryMapper
import org.WenuLink.adapters.camera.CameraCaptureStatus
import org.WenuLink.adapters.camera.CameraMetadata
import org.WenuLink.adapters.camera.ImageMetadata
import org.WenuLink.adapters.camera.SetModeCommand
import org.WenuLink.adapters.camera.StartIntervalShootCommand
import org.WenuLink.adapters.camera.StartRecordCommand
import org.WenuLink.adapters.camera.StopIntervalShootCommand
import org.WenuLink.adapters.camera.StopRecordCommand
import org.WenuLink.adapters.camera.TakePhotoCommand
import org.WenuLink.mavlink.MAVLinkClient
import org.WenuLink.mavlink.messages.ImageStopCaptureCommandLong
import org.WenuLink.mavlink.messages.MessageUtils
import org.WenuLink.mavlink.messages.RequestCameraInformationCommandLong
import org.WenuLink.mavlink.messages.SetCameraModeCommandLong
import org.WenuLink.mavlink.messages.VideoStartCaptureCommandLong
import org.WenuLink.mavlink.messages.VideoStopCaptureCommandLong

/**
 * MAVLinkController class to deal with the camera protocol v2's messages.
 *
 * https://mavlink.io/en/services/camera.html
 *
 */
class CameraController(
    override val client: MAVLinkClient,
    override val handler: WenuLinkHandler,
    private val onSetMessageRate: (messageId: Int, intervalMs: Long) -> Unit
) : IController {
    private val logger by taggedLogger(CameraController::class.java.simpleName)

    private val commandLongRegistry: Map<Int, (msg_command_long) -> Unit> = mapOf(
        MAV_CMD.MAV_CMD_REQUEST_CAMERA_INFORMATION to ::handleCameraInformation,
        MAV_CMD.MAV_CMD_SET_CAMERA_MODE to ::handleSetCameraMode,
        MAV_CMD.MAV_CMD_IMAGE_START_CAPTURE to ::requestStartCapture,
        MAV_CMD.MAV_CMD_IMAGE_STOP_CAPTURE to ::requestStopCapture,
        MAV_CMD.MAV_CMD_VIDEO_START_CAPTURE to ::requestStartRecording,
        MAV_CMD.MAV_CMD_VIDEO_STOP_CAPTURE to ::requestStopRecording
    )

    private val requestLongRegistry: Map<Int, () -> Unit> = mapOf(
        msg_camera_information.MAVLINK_MSG_ID_CAMERA_INFORMATION to ::reportCameras,
        msg_camera_settings.MAVLINK_MSG_ID_CAMERA_SETTINGS to ::reportSettings,
        msg_storage_information.MAVLINK_MSG_ID_STORAGE_INFORMATION to ::sendStorageStatus,
        msg_camera_capture_status.MAVLINK_MSG_ID_CAMERA_CAPTURE_STATUS to ::sendCaptureStatus
    )

    // TODO: Unhandled COMMAND_LONG ID: 2504 MAV_CMD_REQUEST_VIDEO_STREAM_INFORMATION
    override fun processCommandLong(commandLongMsg: msg_command_long): Boolean {
        commandLongRegistry[commandLongMsg.command]?.invoke(commandLongMsg) ?: return false
        return true
    }

    // TODO: Unhandled REQUEST_LONG ID: 269 VIDEO_STREAM_INFORMATION
    override fun processRequestLong(commandLongMsg: msg_command_long): Boolean {
        requestLongRegistry[commandLongMsg.param1.toInt()]?.invoke() ?: return false
        return true
    }

    private fun sendRequestAck(result: Int) = client.sendMessage(
        MessageUtils.msgRequestAck(result)
    )

    private fun sendCommandAck(messageID: Int, result: Int, progress: Int = -1) =
        client.sendMessage(
            MessageUtils.msgCommandAck(messageID, result, progress)
        )

    private fun reportCameras() {
        if (handler.availableCameras.isEmpty()) {
            sendRequestAck(MAV_RESULT.MAV_RESULT_UNSUPPORTED)
            logger.d { "Unsupported: No cameras were detected" }
            sendRequestAck(MAV_RESULT.MAV_RESULT_UNSUPPORTED)
            return
        }

        sendRequestAck(MAV_RESULT.MAV_RESULT_ACCEPTED)
        handler.availableCameras.forEach {
            // Append boot time before send
            client.sendMessage(
                msgCameraInformation(it).apply {
                    logger.d { "CameraReport: $this" }
                    time_boot_ms = handler.systemBootTime
                }
            )
        }
    }

    private fun handleCameraInformation(commandLongMsg: msg_command_long) {
        val params = RequestCameraInformationCommandLong(commandLongMsg)
        if (params.capabilities == true) reportCameras()
    }

    private fun reportSettings() {
        if (handler.availableCameras.isEmpty()) {
            sendRequestAck(MAV_RESULT.MAV_RESULT_UNSUPPORTED)
            logger.d { "Unsupported: No cameras were detected" }
            sendRequestAck(MAV_RESULT.MAV_RESULT_UNSUPPORTED)
            return
        }

        sendRequestAck(MAV_RESULT.MAV_RESULT_ACCEPTED)
        handler.availableCameras.forEach {
            // Append boot time before send
            client.sendMessage(
                msgSettings(it).apply {
                    time_boot_ms = handler.systemBootTime
                }
            )
        }
    }

    private fun handleSetCameraMode(commandLongMsg: msg_command_long) {
        sendCommandAck(commandLongMsg.command, MAV_RESULT.MAV_RESULT_IN_PROGRESS, 0)
        val params = SetCameraModeCommandLong(commandLongMsg)

        handler.dispatchCommand(
            WenuLinkCommand.Camera(SetModeCommand(params.mode, params.id))
        ) { result ->
            sendCommandAck(
                commandLongMsg.command,
                if (result.isOk) {
                    MAV_RESULT.MAV_RESULT_ACCEPTED
                } else {
                    MAV_RESULT.MAV_RESULT_FAILED
                },
                100
            )
        }
    }

    private fun sendStorageStatus() = client.sendMessage(
        msgStorageInformation().apply {
            time_boot_ms = handler.systemBootTime
        }
    )

    private fun sendCaptureStatus() = client.sendMessage(
        msgCaptureStatus(handler.availableCameras.first()).apply {
            time_boot_ms = handler.systemBootTime
        }
    )

    private fun getCamera(targetCamera: Int): CameraMetadata? {
        val cameraInfo = handler.camera.getCamera(targetCamera)
        if (cameraInfo == null) {
            logger.d { "Camera index $targetCamera not found" }
        }
        return cameraInfo
    }

    private fun requestStartCapture(commandLongMsg: msg_command_long) {
        val cameraInfo = getCamera(commandLongMsg.param1.toInt()) ?: run {
            client.sendMessage(
                MessageUtils.msgCommandAck(
                    commandLongMsg.msgid,
                    MAV_RESULT.MAV_RESULT_UNSUPPORTED
                )
            )
            return
        }

        val intervalSeconds = commandLongMsg.param2.toInt()
        val totalPhotos = commandLongMsg.param3.toInt()
        val initSequence = commandLongMsg.param4.toInt()

        handler.camera.photoSeqIndex = initSequence

        // register callback for the duration of this capture session
        handler.onImageCaptured = fun(cameraId, seqIndex) {
            val telemetry = handler.aircraft.telemetry.getData() ?: return
            client.sendMessage(msgImageCaptured(ImageMetadata(seqIndex, true, cameraId, telemetry)))
        }

        val command = if (totalPhotos == 1) {
            WenuLinkCommand.Camera(TakePhotoCommand(cameraInfo.id))
        } else {
            WenuLinkCommand.Camera(StartIntervalShootCommand(cameraInfo.id, intervalSeconds))
        }

        client.sendMessage(
            MessageUtils.msgCommandAck(
                commandLongMsg.msgid,
                MAV_RESULT.MAV_RESULT_ACCEPTED
            )
        )

        handler.dispatchCommand(command) { result ->
            if (result.hasError) {
                logger.w {
                    "requestStartCapture error: ${result.errorReason}"
                }
            }
            if (totalPhotos == 1) handler.onImageCaptured = null
        }
    }

    private fun requestStopCapture(commandLongMsg: msg_command_long) {
        val params = ImageStopCaptureCommandLong(commandLongMsg)
        val cameraInfo = getCamera(params.targetCameraId) ?: run {
            client.sendMessage(
                MessageUtils.msgCommandAck(
                    commandLongMsg.msgid,
                    MAV_RESULT.MAV_RESULT_UNSUPPORTED
                )
            )
            return
        }

        client.sendMessage(
            MessageUtils.msgCommandAck(
                commandLongMsg.msgid,
                MAV_RESULT.MAV_RESULT_ACCEPTED
            )
        )
        handler.onImageCaptured = null
        handler.dispatchCommand(
            WenuLinkCommand.Camera(StopIntervalShootCommand(cameraInfo.id))
        ) { result ->
            if (result.hasError) {
                logger.w {
                    "requestStopCapture error: ${result.errorReason}"
                }
            }
        }
    }

    private fun requestStartRecording(commandLongMsg: msg_command_long) {
        val params = VideoStartCaptureCommandLong(commandLongMsg)
        val cameraInfo: CameraMetadata = getCamera(params.targetCameraId) ?: run {
            client.sendMessage(
                MessageUtils.msgCommandAck(
                    commandLongMsg.msgid,
                    MAV_RESULT.MAV_RESULT_UNSUPPORTED
                )
            )
            return
        }

        client.sendMessage(
            MessageUtils.msgCommandAck(
                commandLongMsg.msgid,
                MAV_RESULT.MAV_RESULT_ACCEPTED
            )
        )

        handler.dispatchCommand(
            WenuLinkCommand.Camera(StartRecordCommand(cameraInfo.id))
        ) { result ->
            if (result.hasError) {
                logger.w { "Error in requestStartRecording: ${result.errorReason}" }
            } else {
                // setting messages frequency
                onSetMessageRate(
                    msg_camera_capture_status.MAVLINK_MSG_ID_CAMERA_CAPTURE_STATUS,
                    ((1f / params.statusFreqHz) * 1000).roundToLong()
                )
            }
        }
    }

    private fun requestStopRecording(commandLongMsg: msg_command_long) {
        val params = VideoStopCaptureCommandLong(commandLongMsg)
        val cameraInfo: CameraMetadata? = getCamera(params.targetCameraId)

        if (cameraInfo == null) {
            client.sendMessage(
                MessageUtils.msgCommandAck(
                    commandLongMsg.msgid,
                    MAV_RESULT.MAV_RESULT_UNSUPPORTED
                )
            )
            return
        }

        client.sendMessage(
            MessageUtils.msgCommandAck(
                commandLongMsg.msgid,
                MAV_RESULT.MAV_RESULT_ACCEPTED
            )
        )

        handler.dispatchCommand(
            WenuLinkCommand.Camera(StopRecordCommand(cameraInfo.id))
        ) { result ->
            if (result.hasError) {
                logger.w { "Error in requestStopRecording: ${result.errorReason}" }
                return@dispatchCommand
            }
            // deactivating messages
            onSetMessageRate(
                msg_camera_capture_status.MAVLINK_MSG_ID_CAMERA_CAPTURE_STATUS,
                -1
            )
        }
    }

    fun msgCameraInformation(cameraInfo: CameraMetadata): msg_camera_information =
        msg_camera_information().apply {
            vendor_name = MessageUtils.toShortArray("DJI", 32)
            model_name = MessageUtils.toShortArray(cameraInfo.name, 32)
            // Parse DJI fw string "MM.mm.pppp" into major/minor/patch
            val parts = cameraInfo.fwVersion.split(".").mapNotNull { it.toIntOrNull() }
            firmware_version = if (parts.size >= 3) {
                MessageUtils.packVersion(
                    parts[0],
                    parts[1],
                    parts[2],
                    FIRMWARE_VERSION_TYPE.FIRMWARE_VERSION_TYPE_OFFICIAL
                )
            } else {
                MessageUtils.packVersion(0, 0, 0, FIRMWARE_VERSION_TYPE.FIRMWARE_VERSION_TYPE_DEV)
            }
            // TODO: check if it can be obtained from SDK
            focal_length = Float.NaN
            sensor_size_h = Float.NaN
            sensor_size_v = Float.NaN
            resolution_h = cameraInfo.width
            resolution_v = cameraInfo.height
            lens_id = 0
            flags = cameraInfo.capabilities
            cam_definition_version = 0
            // TODO: Camera definition file, visit https://mavlink.io/en/services/camera_def.html
            cam_definition_uri = "".toByteArray()
            camera_device_id = cameraInfo.id.toShort()
        }

    fun msgSettings(cameraInfo: CameraMetadata): msg_camera_settings = msg_camera_settings().apply {
        mode_id = cameraInfo.state.mavlinkMode.toShort()
        zoomLevel = Float.NaN
        focusLevel = Float.NaN
        camera_device_id = cameraInfo.id.toShort()
    }

    fun msgStorageInformation(): msg_storage_information =
        // TODO: get from https://developer.dji.com/api-reference/android-api/Components/Camera/DJICamera_DJICameraSDCardState.html
        msg_storage_information().apply {
            storage_id = 1
            storage_count = 1
            status = STORAGE_STATUS.STORAGE_STATUS_READY.toShort()
            total_capacity = 4096f // MB
            used_capacity = 0f // MB
            read_speed = 0f // MB/s
            write_speed = 0f // MB/s
            type = STORAGE_TYPE.STORAGE_TYPE_MICROSD.toShort()
            name = "FakeSDCard".toByteArray()
            storage_usage = STORAGE_USAGE_FLAG.STORAGE_USAGE_FLAG_PHOTO.toShort()
        }

    fun msgCaptureStatus(cameraInfo: CameraMetadata): msg_camera_capture_status {
        // TODO: add proper updates
        var imageStatus = CameraCaptureStatus.IDLE
        var imageInterval = 0f
        var videoStatus = CameraCaptureStatus.IDLE
        var videoTime = 0L
        if (cameraInfo.state.mavlinkMode == CAMERA_MODE.CAMERA_MODE_IMAGE) {
            imageStatus = cameraInfo.state.captureStatus
            imageInterval = cameraInfo.state.captureTime.toFloat()
        } else if (cameraInfo.state.mavlinkMode == CAMERA_MODE.CAMERA_MODE_VIDEO) {
            videoStatus = cameraInfo.state.captureStatus
            videoTime = cameraInfo.state.captureTime
        }
        return msg_camera_capture_status().apply {
            image_status = imageStatus.value.toShort()
            video_status = videoStatus.value.toShort()
            image_interval = imageInterval
            recording_time_ms = videoTime
            // TODO: read true value
            available_capacity = 4000f
            image_count = 0
            camera_device_id = cameraInfo.id.toShort()
        }
    }

    fun msgImageCaptured(imageInfo: ImageMetadata): msg_camera_image_captured {
        val mavData = TelemetryMapper.toMavlink(imageInfo.telemetry)
        return msg_camera_image_captured().apply {
            camera_id = imageInfo.cameraID.toShort()
            lat = mavData.latitude
            lon = mavData.longitude
            alt = mavData.altitude
            relative_alt = mavData.relativeAltitude
            q = OrientationUtils.eulerDegToQuaternion(
                mavData.roll.toDouble(),
                mavData.pitch.toDouble(),
                mavData.yaw.toDouble()
            ).toFloatArray()
            image_index = imageInfo.index
            capture_result = (if (imageInfo.captureOk) 1 else 0).toByte()
            file_url = "".toByteArray()
        }
    }
}
