package org.WenuLink.controllers

import com.MAVLink.common.msg_camera_capture_status
import com.MAVLink.common.msg_camera_image_captured
import com.MAVLink.common.msg_camera_information
import com.MAVLink.common.msg_camera_settings
import com.MAVLink.common.msg_command_long
import com.MAVLink.common.msg_storage_information
import com.MAVLink.enums.CAMERA_CAP_FLAGS
import com.MAVLink.enums.CAMERA_MODE
import com.MAVLink.enums.FIRMWARE_VERSION_TYPE
import com.MAVLink.enums.MAV_CMD
import com.MAVLink.enums.MAV_RESULT
import com.MAVLink.enums.STORAGE_STATUS
import com.MAVLink.enums.STORAGE_TYPE
import com.MAVLink.enums.STORAGE_USAGE_FLAG
import io.getstream.log.taggedLogger
import kotlin.math.roundToLong
import org.WenuLink.adapters.MessageUtils
import org.WenuLink.adapters.OrientationUtils
import org.WenuLink.adapters.aircraft.AircraftHandler
import org.WenuLink.adapters.aircraft.TelemetryMapper
import org.WenuLink.adapters.camera.CameraCaptureStatus
import org.WenuLink.adapters.camera.CameraMetadata
import org.WenuLink.adapters.camera.ImageMetadata
import org.WenuLink.adapters.camera.SetModeCommand
import org.WenuLink.adapters.camera.StartRecordCommand
import org.WenuLink.adapters.camera.StopRecordCommand
import org.WenuLink.adapters.camera.TakePhotoCommand
import org.WenuLink.mavlink.MAVLinkClient

/**
 * MAVLinkController class to deal with the camera protocol v2's messages.
 *
 * https://mavlink.io/en/services/camera.html
 *
 */
class CameraController(override val client: MAVLinkClient, val mainController: MAVLinkController) :
    IController {

    private val logger by taggedLogger(CameraController::class.java.simpleName)

    override fun processCommandLong(
        commandLongMsg: msg_command_long,
        aircraft: AircraftHandler
    ): Boolean {
        var processed = true
        when (commandLongMsg.command) {
            MAV_CMD.MAV_CMD_SET_CAMERA_MODE -> handleSetCameraMode(commandLongMsg, aircraft)
            MAV_CMD.MAV_CMD_IMAGE_START_CAPTURE -> requestStartCapture(commandLongMsg, aircraft)
            MAV_CMD.MAV_CMD_IMAGE_STOP_CAPTURE -> requestStopCapture(commandLongMsg, aircraft)
            MAV_CMD.MAV_CMD_VIDEO_START_CAPTURE -> requestStartRecording(commandLongMsg, aircraft)
            MAV_CMD.MAV_CMD_VIDEO_STOP_CAPTURE -> requestStopRecording(commandLongMsg, aircraft)
            else -> processed = false
        }
        return processed
    }

    override fun processRequestLong(
        commandLongMsg: msg_command_long,
        aircraft: AircraftHandler
    ): Boolean {
        var processed = true
        val requestID = commandLongMsg.param1.toInt()
        when (requestID) {
            msg_camera_information.MAVLINK_MSG_ID_CAMERA_INFORMATION -> reportCameras(aircraft)

            msg_camera_settings.MAVLINK_MSG_ID_CAMERA_SETTINGS -> reportSettings(aircraft)

            msg_storage_information.MAVLINK_MSG_ID_STORAGE_INFORMATION -> sendStorageStatus(
                aircraft
            )

            msg_camera_capture_status.MAVLINK_MSG_ID_CAMERA_CAPTURE_STATUS -> sendCaptureStatus(
                aircraft
            )

            else -> processed = false
        }
        return processed
    }

    private fun sendRequestAck(result: Int) {
        client.sendMessage(
            MessageUtils.msgRequestAck(result)
        )
    }

    private fun sendCommandAck(messageID: Int, result: Int, progress: Int = -1) {
        client.sendMessage(
            MessageUtils.msgCommandAck(messageID, result, progress)
        )
    }

    private fun reportCameras(aircraft: AircraftHandler) {
        if (!aircraft.hasCameras) {
            sendRequestAck(MAV_RESULT.MAV_RESULT_UNSUPPORTED)
            logger.d { "Unsupported: No cameras were detected" }
            return
        }
        sendRequestAck(MAV_RESULT.MAV_RESULT_ACCEPTED)
        aircraft.cameras.list.forEach {
            // Append boot time before send
            val msg = msgCameraInformation(it).apply {
                time_boot_ms = aircraft.systemBootTime
            }
            logger.d { "CameraReport: $msg" }
            client.sendMessage(msg)
        }
    }

    private fun reportSettings(aircraft: AircraftHandler) {
        if (!aircraft.hasCameras) {
            sendRequestAck(MAV_RESULT.MAV_RESULT_UNSUPPORTED)
            logger.d { "Unsupported: No cameras were detected" }
            return
        }
        sendRequestAck(MAV_RESULT.MAV_RESULT_ACCEPTED)
        aircraft.cameras.list.forEach {
            // Append boot time before send
            client.sendMessage(
                msgSettings(it).apply {
                    time_boot_ms = aircraft.systemBootTime
                }
            )
        }
    }

    private fun handleSetCameraMode(commandLongMsg: msg_command_long, aircraft: AircraftHandler) {
        sendCommandAck(
            commandLongMsg.command,
            MAV_RESULT.MAV_RESULT_IN_PROGRESS,
            progress = 0
        )
        val index: Int = commandLongMsg.param1.toInt()
        val mode: Int = commandLongMsg.param2.toInt()

        aircraft.cameras.dispatchCommand(
            SetModeCommand(mode, index)
        ) { error ->
            sendCommandAck(
                commandLongMsg.command,
                if (error == null) {
                    MAV_RESULT.MAV_RESULT_ACCEPTED
                } else {
                    MAV_RESULT.MAV_RESULT_FAILED
                },
                progress = 100
            )
        }
    }

    private fun sendStorageStatus(aircraft: AircraftHandler) {
        client.sendMessage(
            msgStorageInformation().apply {
                time_boot_ms = aircraft.systemBootTime
            }
        )
    }

    private fun sendCaptureStatus(aircraft: AircraftHandler) {
        val cameraInfo: CameraMetadata = aircraft.cameras.list.first()
        client.sendMessage(
            msgCaptureStatus(cameraInfo).apply {
                time_boot_ms = aircraft.systemBootTime
            }
        )
    }

    private fun getCamera(targetCamera: Int, aircraft: AircraftHandler): CameraMetadata? {
        val cameraInfo = aircraft.cameras.getCamera(targetCamera)
        if (cameraInfo == null) {
            logger.d { "Camera index $targetCamera not found" }
        }
        return cameraInfo
    }

    private fun requestShootPhoto(
        commandLongMsg: msg_command_long,
        aircraft: AircraftHandler,
        cameraInfo: CameraMetadata
    ) {
        val intervalTime: Float = commandLongMsg.param2 // seconds
        val initSequence: Int = commandLongMsg.param4.toInt()
        val totalPhotos: Int = commandLongMsg.param3.toInt()

        fun mustCapture() = if (totalPhotos == 0) {
            aircraft.cameras.sequenceIndex != -1
        } else {
            aircraft.cameras.sequenceIndex <= totalPhotos
        }

        aircraft.cameras.sequenceIndex = initSequence

        while (mustCapture()) {
            if ((System.currentTimeMillis() - aircraft.cameras.captureTimestamp) < intervalTime) {
                continue
            }
            if (!aircraft.cameras.captureIdle(cameraInfo.id)) {
                continue
            }

            aircraft.cameras.dispatchCommand(
                TakePhotoCommand(cameraInfo.id)
            )
                { error ->
                    val captureOk = error == null
                    if (!captureOk) {
                        logger.w { "Error in shootPhoto: $error" }
                    }
                    val photoData = ImageMetadata(
                        index = aircraft.cameras.sequenceIndex,
                        captureOk = captureOk,
                        cameraID = cameraInfo.id,
                        telemetry = aircraft.telemetry.getData()!!
                    )

                    client.sendMessage(
                        msgImageCaptured(photoData)
                    )
                }
        }
    }

    private fun requestStartCapture(commandLongMsg: msg_command_long, aircraft: AircraftHandler) {
        val cameraInfo: CameraMetadata? = getCamera(commandLongMsg.param1.toInt(), aircraft)

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

        requestShootPhoto(commandLongMsg, aircraft, cameraInfo)
    }

    private fun requestStopCapture(commandLongMsg: msg_command_long, aircraft: AircraftHandler) {
        val cameraInfo: CameraMetadata? = getCamera(commandLongMsg.param1.toInt(), aircraft)

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

        aircraft.cameras.sequenceIndex = -1
    }

    private fun requestStartRecording(commandLongMsg: msg_command_long, aircraft: AircraftHandler) {
        val cameraInfo: CameraMetadata? = getCamera(commandLongMsg.param3.toInt(), aircraft)

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

        val streamID = commandLongMsg.param1.toInt()
        val statusFreq = commandLongMsg.param2 // Hz

        aircraft.cameras.dispatchCommand(
            StartRecordCommand(cameraInfo.id)
        )
            { error ->
                val captureOk = error == null
                if (!captureOk) {
                    logger.w { "Error in requestStartRecording: $error" }
                } else {
                    // setting messages frequency
                    mainController.setMessageRate(
                        msg_camera_capture_status.MAVLINK_MSG_ID_CAMERA_CAPTURE_STATUS,
                        ((1f / statusFreq) * 1_000).roundToLong()
                    )
                }
            }
    }

    private fun requestStopRecording(commandLongMsg: msg_command_long, aircraft: AircraftHandler) {
        val cameraInfo: CameraMetadata? = getCamera(commandLongMsg.param2.toInt(), aircraft)

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

        val streamID = commandLongMsg.param1.toInt()

        aircraft.cameras.dispatchCommand(
            StopRecordCommand(cameraInfo.id)
        )
            { error ->
                val captureOk = error == null
                if (!captureOk) {
                    logger.w { "Error in requestStopRecording: $error" }
                } else {
                    // deactivating messages
                    mainController.setMessageRate(
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
            firmware_version = MessageUtils.packVersion(
                4,
                18,
                23,
                FIRMWARE_VERSION_TYPE.FIRMWARE_VERSION_TYPE_DEV
            ) // Match SDK version or if possible the Component's own FW version
            // TODO: check if it can be obtained from SDK
            focal_length = Float.NaN
            sensor_size_h = Float.NaN
            sensor_size_v = Float.NaN
            resolution_h = cameraInfo.width
            resolution_v = cameraInfo.height
            lens_id = 0
            flags = CAMERA_CAP_FLAGS.CAMERA_CAP_FLAGS_CAPTURE_IMAGE.toLong() or
                CAMERA_CAP_FLAGS.CAMERA_CAP_FLAGS_CAPTURE_VIDEO.toLong() or
                CAMERA_CAP_FLAGS.CAMERA_CAP_FLAGS_HAS_MODES.toLong()
            cam_definition_version = cameraInfo.fwVersion.toInt()
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

    fun msgStorageInformation(): msg_storage_information {
        // TODO: get from https://developer.dji.com/api-reference/android-api/Components/Camera/DJICamera_DJICameraSDCardState.html
        return msg_storage_information().apply {
            storage_id = 1
            storage_count = 1
            status = STORAGE_STATUS.STORAGE_STATUS_READY.toShort()
            total_capacity = 4096F // MB
            used_capacity = 0F // MB
            read_speed = 0F // MB/s
            write_speed = 0F // MB/s
            type = STORAGE_TYPE.STORAGE_TYPE_MICROSD.toShort()
            name = "FakeSDCard".toByteArray()
            storage_usage = STORAGE_USAGE_FLAG.STORAGE_USAGE_FLAG_PHOTO.toShort()
        }
    }

    fun msgCaptureStatus(cameraInfo: CameraMetadata): msg_camera_capture_status {
        // TODO: add proper updates
        var imageStatus = CameraCaptureStatus.IDLE
        var imageInterval: Float = 0f
        var videoStatus = CameraCaptureStatus.IDLE
        var videoTime: Long = 0
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
            available_capacity = 4000F
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
                rollDeg = mavData.roll.toDouble(),
                pitchDeg = mavData.pitch.toDouble(),
                yawDeg = mavData.yaw.toDouble()
            ).toFloatArray()
            image_index = imageInfo.index
            capture_result = imageInfo.captureOk.toString().toByte()
            file_url = "".toByteArray()
        }
    }
}
