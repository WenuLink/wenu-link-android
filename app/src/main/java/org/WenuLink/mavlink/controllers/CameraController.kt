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
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import org.WenuLink.adapters.OrientationUtils
import org.WenuLink.adapters.WenuLinkCommand
import org.WenuLink.adapters.WenuLinkHandler
import org.WenuLink.adapters.aircraft.TelemetryMapper
import org.WenuLink.adapters.camera.CameraMetadata
import org.WenuLink.adapters.camera.ImageMetadata
import org.WenuLink.adapters.camera.SetModeCommand
import org.WenuLink.adapters.camera.StartIntervalShootCommand
import org.WenuLink.adapters.camera.StartRecordCommand
import org.WenuLink.adapters.camera.StopIntervalShootCommand
import org.WenuLink.adapters.camera.StopRecordCommand
import org.WenuLink.adapters.camera.TakePhotoCommand
import org.WenuLink.mavlink.MAVLinkClient
import org.WenuLink.mavlink.messages.ImageStartCaptureMessage
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
    private val onSetMessageRate: (messageId: Int, microSeconds: Long) -> Unit
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
        val params = SetCameraModeCommandLong(commandLongMsg)
        val cameras: List<CameraMetadata> = getCameras(params.id)

        if (cameras.isEmpty()) {
            client.sendMessage(
                MessageUtils.msgCommandAck(
                    commandLongMsg.msgid,
                    MAV_RESULT.MAV_RESULT_UNSUPPORTED
                )
            )
            return
        }

        sendCommandAck(commandLongMsg.command, MAV_RESULT.MAV_RESULT_IN_PROGRESS, 50)

        cameras.forEach { cameraInfo ->
            handler.dispatchCommand(
                WenuLinkCommand.Camera(SetModeCommand(params.mode, cameraInfo.id))
            ) { result ->
                if (result.hasError) {
                    logger.w {
                        "Error in set mode mode ${params.mode} for camera ID ${cameraInfo.id}"
                    }
                }
                sendCommandAck(
                    commandLongMsg.command,
                    if (result.isOk) {
                        MAV_RESULT.MAV_RESULT_ACCEPTED
                    } else {
                        MAV_RESULT.MAV_RESULT_FAILED
                    }
                )
            }
        }
    }

    private fun sendStorageStatus() = client.sendMessage(
        msgStorageInformation().apply {
            time_boot_ms = handler.systemBootTime
        }
    )

    private fun sendCaptureStatus() = client.sendMessage(
        msgCaptureStatus(handler.availableCameras.first())
    )

    private fun getCameras(targetCamera: Int): List<CameraMetadata> = if (targetCamera == 0) {
        handler.camera.availableCameras.also {
            if (it.isEmpty()) logger.w { "No cameras found" }
        }
    } else {
        handler.camera.getCamera(targetCamera)?.let { listOf(it) } ?: run {
            logger.d { "Camera id $targetCamera not found" }
            emptyList()
        }
    }

    private fun requestStartCapture(commandLongMsg: msg_command_long) {
        val params = ImageStartCaptureMessage(commandLongMsg)
        val cameras: List<CameraMetadata> = getCameras(params.targetCameraId)

        if (cameras.isEmpty()) {
            client.sendMessage(
                MessageUtils.msgCommandAck(
                    commandLongMsg.msgid,
                    MAV_RESULT.MAV_RESULT_UNSUPPORTED
                )
            )
            return
        }

        handler.camera.photoSeqIndex = params.sequenceNumber

        // register callback for the duration of this capture session
        handler.onImageCaptured = fun(cameraId, seqIndex) {
            val telemetry = handler.aircraft.currentTelemetry ?: return
            client.sendMessage(msgImageCaptured(ImageMetadata(seqIndex, true, cameraId, telemetry)))
        }

        client.sendMessage(
            MessageUtils.msgCommandAck(
                commandLongMsg.msgid,
                MAV_RESULT.MAV_RESULT_ACCEPTED
            )
        )

        cameras.forEach { cameraInfo ->
            val command = if (params.totalImages == 1) {
                WenuLinkCommand.Camera(TakePhotoCommand(cameraInfo.id))
            } else {
                WenuLinkCommand.Camera(
                    StartIntervalShootCommand(cameraInfo.id, params.intervalSec.roundToInt())
                )
            }

            handler.dispatchCommand(command) { result ->
                if (result.hasError) logger.w { "requestStartCapture error: ${result.errorReason}" }
                if (params.totalImages == 1) handler.onImageCaptured = null
            }
        }
    }

    private fun requestStopCapture(commandLongMsg: msg_command_long) {
        val params = ImageStopCaptureCommandLong(commandLongMsg)
        val cameras: List<CameraMetadata> = getCameras(params.targetCameraId)

        if (cameras.isEmpty()) {
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
        cameras.forEach { cameraInfo ->
            handler.dispatchCommand(
                WenuLinkCommand.Camera(StopIntervalShootCommand(cameraInfo.id))
            ) { result ->
                if (result.hasError) logger.w { "requestStopCapture error: ${result.errorReason}" }
            }
        }
    }

    private fun requestStartRecording(commandLongMsg: msg_command_long) {
        val params = VideoStartCaptureCommandLong(commandLongMsg)
        val cameras: List<CameraMetadata> = getCameras(params.targetCameraId)

        if (cameras.isEmpty()) {
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

        cameras.forEach { cameraInfo ->
            handler.dispatchCommand(
                WenuLinkCommand.Camera(StartRecordCommand(cameraInfo.id))
            ) { result ->
                if (result.hasError) {
                    logger.w { "Error in requestStartRecording: ${result.errorReason}" }
                } else if (params.statusFreqHz == 0f) {
                    logger.i { "No messages for camera capture status" }
                } else {
                    val intervalUs = ((1f / params.statusFreqHz) * 1_000_000).roundToLong()
                    logger.d { "record started, sending capture status every $intervalUs us" }
                    // setting messages frequency
                    onSetMessageRate(
                        msg_camera_capture_status.MAVLINK_MSG_ID_CAMERA_CAPTURE_STATUS,
                        intervalUs
                    )
                }
            }
        }
    }

    private fun requestStopRecording(commandLongMsg: msg_command_long) {
        val params = VideoStopCaptureCommandLong(commandLongMsg)
        val cameras: List<CameraMetadata> = getCameras(params.targetCameraId)

        if (cameras.isEmpty()) {
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

        cameras.forEach { cameraInfo ->
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

    fun msgStorageInformation(): msg_storage_information = msg_storage_information().apply {
        // TODO: get from https://developer.dji.com/api-reference/android-api/Components/Camera/DJICamera_DJICameraSDCardState.html
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

    fun msgCaptureStatus(cameraInfo: CameraMetadata): msg_camera_capture_status =
        msg_camera_capture_status().apply {
            when (cameraInfo.state.mavlinkMode) {
                CAMERA_MODE.CAMERA_MODE_IMAGE -> {
                    image_status = cameraInfo.state.captureStatus.value.toShort()
                    image_interval = cameraInfo.state.timeMillis / 1000f // ms -> s
                }

                CAMERA_MODE.CAMERA_MODE_VIDEO -> {
                    video_status = cameraInfo.state.captureStatus.value.toShort()
                    recording_time_ms = cameraInfo.state.timeMillis
                }
            }

            time_boot_ms = handler.systemBootTime
            // TODO: read true value
            available_capacity = 4000f
            image_count = cameraInfo.state.totalPhotos
            camera_device_id = cameraInfo.id.toShort()
        }

    fun msgImageCaptured(imageInfo: ImageMetadata): msg_camera_image_captured =
        msg_camera_image_captured().apply {
            val mavData = TelemetryMapper.toMavlink(imageInfo.telemetry)
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
