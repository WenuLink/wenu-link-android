package org.WenuLink.controllers

import com.MAVLink.Messages.MAVLinkMessage
import com.MAVLink.common.msg_extended_sys_state
import com.MAVLink.common.msg_global_position_int
import com.MAVLink.common.msg_gps_raw_int
import com.MAVLink.common.msg_home_position
import com.MAVLink.common.msg_local_position_ned
import com.MAVLink.common.msg_mission_ack
import com.MAVLink.common.msg_mission_clear_all
import com.MAVLink.common.msg_mission_count
import com.MAVLink.common.msg_mission_current
import com.MAVLink.common.msg_mission_item_int
import com.MAVLink.common.msg_mission_item_reached
import com.MAVLink.common.msg_mission_request_int
import com.MAVLink.common.msg_mission_request_list
import com.MAVLink.common.msg_statustext
import com.MAVLink.enums.GPS_FIX_TYPE
import com.MAVLink.enums.MAV_CMD
import com.MAVLink.enums.MAV_FRAME
import com.MAVLink.enums.MAV_MISSION_RESULT
import com.MAVLink.enums.MAV_MISSION_TYPE
import com.MAVLink.enums.MAV_SEVERITY
import com.MAVLink.enums.MAV_VTOL_STATE
import com.MAVLink.enums.MISSION_STATE
import io.getstream.log.taggedLogger
import org.WenuLink.adapters.AircraftHandler
import org.WenuLink.adapters.Coordinates3D
import org.WenuLink.adapters.TelemetryHandler
import org.WenuLink.adapters.MessageUtils
import org.WenuLink.mavlink.MAVLinkClient
import org.WenuLink.sdk.MissionManager
import kotlin.getValue
import kotlin.math.max
import kotlin.math.min

/**
 * MAVLinkController class to deal with the mission service and related MAVLink messages.
 *
 * https://mavlink.io/en/services/mission.html
 *
 * https://developer.dji.com/api-reference/android-api/Components/Missions/DJIWaypointMissionOperator.html
 * https://developer.dji.com/api-reference/android-api/Components/Missions/DJIMissionControl.html
 *
 */
class NavigationController (
    override val client: MAVLinkClient,
) : IController {
    private val logger by taggedLogger("NavigationController")
    private val missionItems = ArrayList<msg_mission_item_int>()
    private var isReceivingMission = false
    private var isTakeoffReceived = false
    private var numberOfExpectedItems = -1
    private var numberOfStoredItems = 0
    private var flightSpeed: Float = 5f
    private var maxRetryTimes = 5
    private var currentRetryTimes = 0
    private var currentItemSequence = 0
    private lateinit var takeOffCoordinates: Coordinates3D
    private var rtlWhenFinish: Boolean = false
    var wasMissionDownloaded = false
        private set
    // TODO: implement missionStart and missionStop
    var isMissionRunning = false
        private set
    // TODO: probably should need fix threading and/or execution scope

    init {
        populateMissionList()
    }

    fun getMissionId(): Long {
        return if (missionItems.isNotEmpty() && !isReceivingMission) {
            202512 // random number
        } else 0
    }

    override fun processMessage(msg: MAVLinkMessage, aircraft: AircraftHandler): Boolean {
        var processed = true
        when (msg.msgid) {
            msg_mission_count.MAVLINK_MSG_ID_MISSION_COUNT -> createNewMission(msg)
            msg_mission_item_int.MAVLINK_MSG_ID_MISSION_ITEM_INT -> prepareMissionItem(msg, aircraft)
            msg_mission_request_list.MAVLINK_MSG_ID_MISSION_REQUEST_LIST -> sendMissionCount()
            msg_mission_request_int.MAVLINK_MSG_ID_MISSION_REQUEST_INT -> sendMissionItem(msg)
            msg_mission_clear_all.MAVLINK_MSG_ID_MISSION_CLEAR_ALL -> sendMissionClear()
            msg_mission_ack.MAVLINK_MSG_ID_MISSION_ACK -> processAck(msg)
            else -> processed = false
        }
        return processed
    }

    override fun createMessage(messageID: Int, telemetry: TelemetryHandler, aircraft: AircraftHandler): MAVLinkMessage? {
        return when (messageID) {
            msg_mission_current.MAVLINK_MSG_ID_MISSION_CURRENT -> msgMissionCurrent()
            msg_home_position.MAVLINK_MSG_ID_HOME_POSITION -> msgHomePosition(aircraft)
            msg_extended_sys_state.MAVLINK_MSG_ID_EXTENDED_SYS_STATE -> msgExtendedSys(aircraft)
            msg_gps_raw_int.MAVLINK_MSG_ID_GPS_RAW_INT -> msgRawGPSInt(telemetry)
            msg_global_position_int.MAVLINK_MSG_ID_GLOBAL_POSITION_INT -> msgGlobalPositionInt(telemetry)
            msg_local_position_ned.MAVLINK_MSG_ID_LOCAL_POSITION_NED -> msgLocalPositionNed(telemetry, aircraft)
            // TODO: GPS global origin
            else -> null
        }
    }

    private val waypointTypes = mapOf(
        // movement
        Pair(10, MAV_CMD.MAV_CMD_NAV_TAKEOFF),
        Pair(11, MAV_CMD.MAV_CMD_NAV_WAYPOINT),
        Pair(12, MAV_CMD.MAV_CMD_NAV_DELAY),
        // orientation
        Pair(20, MAV_CMD.MAV_CMD_CONDITION_YAW),
        Pair(21, MAV_CMD.MAV_CMD_DO_DIGICAM_CONTROL),
        // camera triggers
        Pair(30, MAV_CMD.MAV_CMD_SET_CAMERA_ZOOM),
        Pair(31, MAV_CMD.MAV_CMD_SET_CAMERA_FOCUS),
        Pair(32, MAV_CMD.MAV_CMD_IMAGE_START_CAPTURE),
        Pair(33, MAV_CMD.MAV_CMD_VIDEO_START_CAPTURE),
        Pair(34, MAV_CMD.MAV_CMD_VIDEO_STOP_CAPTURE),
    )

    fun getMAVLinkType(idDJI: Int): Int {
        return waypointTypes[idDJI] ?: -1
    }

    fun flushMissionItemList() {
        missionItems.clear()
        MissionManager.flushWaypoints()
        numberOfExpectedItems = -1
        numberOfStoredItems = 0
    }

    fun appendMissionItem(msg: msg_mission_item_int) {
        numberOfStoredItems += 1
        missionItems.add(msg)
    }

    fun createMissionItemMsg(
        itemSeq: Int,
        coordX: Int,
        coordY: Int,
        coordZ: Float,
        command: Int? = null
    ): msg_mission_item_int {
        val msg = msg_mission_item_int()
        msg.seq = itemSeq
        msg.frame = MAV_FRAME.MAV_FRAME_GLOBAL_RELATIVE_ALT.toShort()
        msg.command = command ?: MAV_CMD.MAV_CMD_NAV_WAYPOINT
        msg.mission_type = MAV_MISSION_TYPE.MAV_MISSION_TYPE_MISSION.toShort()
        msg.x = coordX
        msg.y = coordY
        msg.z = coordZ
        return msg
    }

    fun populateMissionList() {
        val currentMission = MissionManager.getWaypointMission() ?: return
        logger.d { "populateMissionList: ${currentMission.waypointList.size} WP" }
        var itemSeq = 0
        for (wp in currentMission.waypointList) {
            val coordX = MessageUtils.coordinateDJI2MAVLink(wp.coordinate.latitude)
            val coordY = MessageUtils.coordinateDJI2MAVLink(wp.coordinate.longitude)
            val coordZ = wp.altitude
            // Assign the correct command
            if (itemSeq == 0) {
                // Assume the first mission point is a takeoff...
                logger.d { "Mission Command : Takeoff" }
                appendMissionItem(
                    createMissionItemMsg(
                        itemSeq,
                        coordX,
                        coordY,
                        coordZ,
                        MAV_CMD.MAV_CMD_NAV_TAKEOFF
                    )
                )
                itemSeq += 1
            } else {
                logger.d { "Mission Command : Waypoint" }
                appendMissionItem(createMissionItemMsg(itemSeq, coordX, coordY, coordZ))
                itemSeq += 1

                for (action in wp.waypointActions) {
                    logger.d { "Waypoint Action : $action" }
                    missionItems.add(createMissionItemMsg(
                        itemSeq,
                        coordX,
                        coordY,
                        coordZ,
                        getMAVLinkType(MissionManager.waypointActionIndex(action))
                    ))
                    itemSeq += 1
                }
            }
        }
    }

    fun sendAckAnswer(type: Int) {
        val msg = msg_mission_ack()
        msg.type = type.toShort()
        msg.opaque_id = getMissionId()
        client.sendMessage(msg)
    }

    fun processAck(msg: MAVLinkMessage) {
        val ackMsg = msg as msg_mission_ack
        wasMissionDownloaded = true
        logger.d { "ACK type: ${ackMsg.type} missionType ${ackMsg.mission_type}" }
    }

    fun sendMissionCount() {
        val msg = msg_mission_count()
        msg.mission_type = MAV_MISSION_TYPE.MAV_MISSION_TYPE_MISSION.toShort()
        msg.count = numberOfStoredItems
        msg.opaque_id = getMissionId()
        logger.d { "sendMissionCount: ${msg.count}" }
        client.sendMessage(msg)
    }

    fun sendMissionItem(msg: MAVLinkMessage) {
        val itemMsg = msg as msg_mission_request_int
        val idx = itemMsg.seq
        logger.d { "sendMissionItem #$idx" }
        if (missionItems.isNotEmpty())
            client.sendMessage(missionItems[idx])
    }

    fun sendMissionClear() {
        flushMissionItemList()
        sendAckAnswer(MAV_MISSION_RESULT.MAV_MISSION_ACCEPTED)
    }

    fun requestMissionItem(seq: Int) {
        logger.d { "requestMissionItem #$seq" }
        val msg = msg_mission_request_int()
        msg.seq = seq
        msg.mission_type = MAV_MISSION_TYPE.MAV_MISSION_TYPE_MISSION.toShort()
        client.sendMessage(msg)
    }

    fun createNewMission(msg: MAVLinkMessage) {
        logger.d { "createNewMission" }
        val missionMsg = msg as msg_mission_count
        // Is this message to this system...
        if (!client.isTargetSystem(missionMsg.target_system)) {
            return
        }
        // TODO: validate READY_TO_UPLOAD state
        logger.d { "Mission state: ${MissionManager.getWaypointMissionState()}" }
        if (MissionManager.getWaypointMissionState().name != "READY_TO_UPLOAD") return

        // TODO: Stop mission execution first if needed
//        MissionManager.runMission(false) { error ->
//            if (error != null) {
//                logger.e { "Unable to create new mission, current mission didn't stop" }
//            } else {
        // flush mission items
        flushMissionItemList()
        isReceivingMission = true
        isTakeoffReceived = false
        rtlWhenFinish = false

        numberOfExpectedItems = missionMsg.count - 1

        // Only creates a new mission if has elements on it
        if (numberOfExpectedItems > 0) {
            // ask for the first item an iterate over count
            logger.d { "Creating new mission with ${missionMsg.count} items" }

            // Request first mission item...
            requestMissionItem(0)
            // TODO: timeout waiting start?
        }
//            }
//        }
    }

    fun getItemCoordinates(itemMsg: msg_mission_item_int, aircraft: AircraftHandler): Coordinates3D {
        val latitude = MessageUtils.coordinateMAVLink2DJI(itemMsg.x)
        val longitude = MessageUtils.coordinateMAVLink2DJI(itemMsg.y)
        return Coordinates3D(latitude, longitude, itemMsg.z)
    }

    fun processTakeoff(itemMsg: msg_mission_item_int, aircraft: AircraftHandler) {
        val coordinates = getItemCoordinates(itemMsg, aircraft)
        takeOffCoordinates = coordinates
        if (isReceivingMission && !isTakeoffReceived) {
            MissionManager.addTakeoffWP(coordinates.alt)
            isTakeoffReceived = true
        } else {
            logger.d { "processTakeoff" }
            aircraft.takeOff()
        }
    }

    fun processLanding(aircraft: AircraftHandler) {
        logger.d { "processLanding" }
        if (isReceivingMission) {
            // TODO: Complete landing + disarm sequence
            MissionManager.addLandingWP()
        } else {
            // TODO: reduce altitude first?
            aircraft.land()
        }
    }

    fun processWaypoint(itemMsg: msg_mission_item_int, aircraft: AircraftHandler) {
        val seq = itemMsg.seq
        val coordinates = getItemCoordinates(itemMsg, aircraft)
        val delay = itemMsg.param1.toInt()
        val yaw: Float = itemMsg.param4

        val frameReference = itemMsg.frame.toInt()
        // We only support the following frame models:
        // 0 = Global (WGS84) coordinate frame + altitude relative to mean sea level (MSL).
        // 3 = Global (WGS84) coordinate frame + altitude relative to the home position.
        if (frameReference != 0 && frameReference != 3) {
            logger.w { "frameReference: $frameReference is not available" }
            sendAckAnswer(MAV_MISSION_RESULT.MAV_MISSION_UNSUPPORTED_FRAME)
            return
        }

        if (isReceivingMission) {
            // if the first element and no TakeOff was received, add the TakeOff
            // and then displace towards the location
            if (seq == 0) processTakeoff(itemMsg, aircraft)
            // Append the waypoint and actions
            MissionManager.addWaypoint(coordinates.lat, coordinates.long, coordinates.alt)
            // Delay
            if (delay > 0) MissionManager.addActionDelay(delay)  // milliseconds
            // Yaw
            if (yaw > 0) MissionManager.addActionRotate((yaw * 180.0 / 3.141592).toInt()) // +-180 deg

            // TODO: should keep track of initial altitude? maybe is better to use the home location
            // TODO: take care of different frameReference
            // TODO: Limit to a max altitude and minimum altitude
            // TODO: check altitude conversion

            logger.d { "Waypoint: (${coordinates.lat}, ${coordinates.long})  at ${coordinates.alt} : Yaw $yaw deg - Delay $delay" }
        } else {
            logger.d { "processWaypoint: flyTo()" }
//            aircraft.flyTo()
        }
    }

    fun processReturnToLaunch(itemMsg: msg_mission_item_int, aircraft: AircraftHandler) {
        if (isReceivingMission) {
            if (itemMsg.seq == numberOfExpectedItems) rtlWhenFinish = true
            logger.d { "processReturnToLaunch: $rtlWhenFinish" }
        }
        else {
            logger.d { "processReturnToLaunch" }
//            aircraft.returnToLaunch()
        }

    }

    fun processDelay(itemMsg: msg_mission_item_int) {
        logger.d { "processDelay" }
        val seconds = itemMsg.param1
//                MissionManager.addWaypoint(latitude, longitude, altitude)
        MissionManager.addActionDelay(seconds.toInt())
    }

    fun processCameraPhoto(itemMsg: msg_mission_item_int) {
        logger.d { "processCameraPhoto" }
//                MissionManager.addWaypoint(latitude, longitude, altitude)
        MissionManager.addActionTakePhoto()
    }

    fun processCameraStartRecord(itemMsg: msg_mission_item_int) {
        logger.d { "processCameraStartRecord" }
//                MissionManager.addWaypoint(latitude, longitude, altitude)
        MissionManager.addActionStartRecord()
    }

    fun processCameraStopRecord(itemMsg: msg_mission_item_int) {
        logger.d { "processCameraStopRecord" }
//                MissionManager.addWaypoint(latitude, longitude, altitude)
        MissionManager.addActionStopRecord()
    }

    fun processRotate(itemMsg: msg_mission_item_int) {
        logger.d { "processRotate" }
        val angle = itemMsg.param1 - 180  // [0, 360] -> [-180, 180]
//                MissionManager.addWaypoint(latitude, longitude, altitude)
        MissionManager.addActionRotate(angle.toInt())
    }

    fun processNewSpeed(itemMsg: msg_mission_item_int) {
        logger.d { "processNewSpeed" }
        val speed = itemMsg.param2
        if (speed < -15 || speed > 15) {
            logger.w { "New speed limits [-15, 15], clipping value" }
        }
        flightSpeed = min(max(speed, -15f), 15f)
    }

    fun processGimbalPitch(itemMsg: msg_mission_item_int) {
        logger.d { "processGimbalPitch" }
        val pitch = itemMsg.param1
//                MissionManager.addWaypoint(latitude, longitude, altitude)
        MissionManager.addActionGimbalPitch(pitch.toInt())
    }

    fun processMissionItem(itemMsg: msg_mission_item_int, aircraft: AircraftHandler): Boolean {
        var commandAccepted = true

        when (itemMsg.command) {
            MAV_CMD.MAV_CMD_NAV_TAKEOFF -> processTakeoff(itemMsg, aircraft)
            MAV_CMD.MAV_CMD_NAV_LAND -> processLanding(aircraft)
            MAV_CMD.MAV_CMD_NAV_WAYPOINT -> processWaypoint(itemMsg, aircraft)
            MAV_CMD.MAV_CMD_NAV_RETURN_TO_LAUNCH -> processReturnToLaunch(itemMsg, aircraft)
            MAV_CMD.MAV_CMD_NAV_DELAY -> processDelay(itemMsg)

            // TODO: split camera related messages to CameraController as corresponding service
            MAV_CMD.MAV_CMD_IMAGE_START_CAPTURE -> processCameraPhoto(itemMsg)
            MAV_CMD.MAV_CMD_VIDEO_START_CAPTURE -> processCameraStartRecord(itemMsg)
            MAV_CMD.MAV_CMD_VIDEO_STOP_CAPTURE -> processCameraStopRecord(itemMsg)
            MAV_CMD.MAV_CMD_CONDITION_YAW -> processRotate(itemMsg)
            MAV_CMD.MAV_CMD_DO_CHANGE_SPEED -> processNewSpeed(itemMsg)
            MAV_CMD.MAV_CMD_DO_MOUNT_CONTROL -> processGimbalPitch(itemMsg)
            MAV_CMD.MAV_CMD_DO_DIGICAM_CONTROL -> logger.d { "MAV_CMD_DO_DIGICAM_CONTROL" }
            MAV_CMD.MAV_CMD_SET_CAMERA_ZOOM -> logger.d { "MAV_CMD_SET_CAMERA_ZOOM" }
            MAV_CMD.MAV_CMD_SET_CAMERA_FOCUS -> logger.d { "MAV_CMD_SET_CAMERA_FOCUS" }
            MAV_CMD.MAV_CMD_DO_SET_MISSION_CURRENT -> setCurrentMissionItem(itemMsg, aircraft)

            else -> commandAccepted = false
        }
        return commandAccepted
    }

    fun ackReceivedItem(sequence: Int) {
        logger.d { "ackReceivedItem #$sequence" }
        val msg = msg_mission_item_reached()
        msg.seq = sequence
        client.sendMessage(msg)
    }

    fun buildWaypointMission(startIndex: Int, aircraft: AircraftHandler, onResult: (Boolean, String?) -> Unit) {
        logger.d { "buildWaypointMission" }
        var currentIndex = startIndex
        while (currentIndex < numberOfStoredItems) {
            processMissionItem(missionItems[currentIndex], aircraft)
            currentIndex += 1
        }
        MissionManager.buildMission(flightSpeed, rtlWhenFinish=rtlWhenFinish, onResult = onResult)
    }

    fun prepareMissionItem(msg: MAVLinkMessage, aircraft: AircraftHandler) {
        logger.d { "processMissionItem" }
        val itemMsg = msg as msg_mission_item_int
        if (!client.isTargetSystem(itemMsg.target_system)) {
            return
        }
        // Validate sequence
        val expectedSeq = missionItems.size
        if (itemMsg.seq != expectedSeq && isReceivingMission) {
            logger.e { "Received item #${itemMsg.seq} instead #${expectedSeq}, re-requesting..." }
            if (currentRetryTimes < maxRetryTimes) {
                requestMissionItem(expectedSeq)
                currentRetryTimes += 1
            } else logger.w { "Max re-requesting attempts reached" }

            return
        }
        // Store item and request next or upload the mission
        if (isReceivingMission) {
            appendMissionItem(itemMsg)
            ackReceivedItem(expectedSeq)

            if (itemMsg.seq < numberOfExpectedItems) {
                // request next mission item
                requestMissionItem(expectedSeq + 1)
            } else {
                // reached the end of the mission items
                buildWaypointMission(0, aircraft) { success, error ->
                    logger.i { "Mission accepted: $success" }
                    if (error != null) logger.e { "buildWaypointMission: $error" }
                }
                sendAckAnswer(MAV_MISSION_RESULT.MAV_MISSION_ACCEPTED)
                isReceivingMission = false
            }
        }
    }

    fun getMissionState(): Int {
        return if (isMissionRunning) MISSION_STATE.MISSION_STATE_ACTIVE
        else MISSION_STATE.MISSION_STATE_NO_MISSION
    }

    fun sendCurrentMission(sequence: Int) {
        logger.d { "sendCurrentMission" }
        val msg = msg_mission_current()
        msg.seq = sequence
        msg.mission_id = getMissionId()
        msg.mission_state = getMissionState().toShort()
        client.sendMessage(msg)
    }

    fun sendStatusText(error: String, severity: Int) {
        logger.d { "sendStatusText" }
        val msg = msg_statustext()
        msg.text = error.toByteArray()
        msg.severity = severity.toShort()
        client.sendMessage(msg)
    }

    fun setCurrentMissionItem(msg: msg_mission_item_int, aircraft: AircraftHandler) {
        logger.d { "setCurrentMissionItem" }
        MissionManager.runMission(false) { error ->
            if (error == null) {
                val reset = msg.param2 == 1f
                val requestedSeq = msg.param1.toInt()
                // must reset mission
                currentItemSequence = if (requestedSeq == -1 || reset) 0
                else requestedSeq

                buildWaypointMission(currentItemSequence, aircraft) { success, error ->
                    if (success) {
                        sendCurrentMission(currentItemSequence)
                        MissionManager.runMission(true) { error ->
                            if (error != null) logger.e { "Unable to start mission: $error" }
                        }
                    } else if (error != null) {
                        sendStatusText(error, MAV_SEVERITY.MAV_SEVERITY_ERROR)
                    }
                }
            } else {
                logger.e { "Unable to setCurrentMissionItem: $error" }
            }
        }
    }

    fun msgMissionCurrent(): MAVLinkMessage {
        val msg = msg_mission_current()
        msg.seq = currentItemSequence
        msg.mission_state = getMissionState().toShort()
        return msg
    }

    // TODO: start, pause, and resume procedures


    fun msgHomePosition(aircraft: AircraftHandler): MAVLinkMessage? {
        val coordinates = aircraft.getHomePosition() ?: return null
        val msg = msg_home_position()
        msg.latitude = MessageUtils.coordinateDJI2MAVLink(coordinates.lat)
        msg.longitude = MessageUtils.coordinateDJI2MAVLink(coordinates.long)
        msg.altitude = MessageUtils.altitudeDJI2MAVLink(coordinates.alt)
        return msg
    }

    fun msgExtendedSys(aircraft: AircraftHandler): MAVLinkMessage {
        val msg = msg_extended_sys_state()
        msg.landed_state = aircraft.landedState.toShort()
        msg.vtol_state = MAV_VTOL_STATE.MAV_VTOL_STATE_MC.toShort()
        return msg
    }

    fun msgGlobalPositionInt(telemetry: TelemetryHandler): MAVLinkMessage? {
        val telemetryData = telemetry.getTelemetryData() ?: return null
        val msg = msg_global_position_int()
        msg.lat = MessageUtils.coordinateDJI2MAVLink(telemetryData.latitude)
        msg.lon = MessageUtils.coordinateDJI2MAVLink(telemetryData.longitude)
        msg.alt = MessageUtils.altitudeDJI2MAVLink(telemetryData.altitude)
        // NOTE: Commented out this field, because msg.relative_alt seems to be intended for altitude above the current terrain,
        // but DJI reports altitude above home point.
        // Mavlink: Millimeters above ground (unspecified: presumably above home point?)
        // DJI: relative altitude of the aircraft relative to take off location, measured by barometer, in meters.
        msg.relative_alt = MessageUtils.altitudeDJI2MAVLink(telemetryData.altitude)
        msg.vx = (telemetryData.velocityX * 100).toInt().toShort()
        msg.vy = (telemetryData.velocityY * 100).toInt().toShort()
        msg.vz = (telemetryData.velocityZ * 100).toInt().toShort()
        var yaw = telemetryData.yaw
        if (yaw < 0) yaw += 360
        msg.hdg = (yaw * 100).toInt()
//        client.sendMessage(msg)
        return msg
    }

    fun msgRawGPSInt(telemetry: TelemetryHandler): MAVLinkMessage? {
        val telemetryData = telemetry.getTelemetryData() ?: return null

        val msg = msg_gps_raw_int()
        if (telemetry.isSimulationActive()) {
            msg.fix_type = GPS_FIX_TYPE.GPS_FIX_TYPE_NO_GPS.toShort()
            return msg
        }
        msg.time_usec = MessageUtils.getMicroTime()
        msg.lat = MessageUtils.coordinateDJI2MAVLink(telemetryData.latitude)
        msg.lon = MessageUtils.coordinateDJI2MAVLink(telemetryData.longitude)
        msg.satellites_visible = telemetryData.satelliteCount.toShort()
        // DJI reports signal quality on a scale of 1-11
        // Mavlink has separate codes for fix type.
        if (telemetryData.gpsLevel[0] || telemetryData.gpsLevel[1]) {
            msg.fix_type = GPS_FIX_TYPE.GPS_FIX_TYPE_NO_FIX.toShort()
        }
        else if (telemetryData.gpsLevel[2]) {
            msg.fix_type = GPS_FIX_TYPE.GPS_FIX_TYPE_2D_FIX.toShort()
        }
        else if (telemetryData.gpsLevel[3] || telemetryData.gpsLevel[4] || telemetryData.gpsLevel[5]) {
            msg.fix_type = GPS_FIX_TYPE.GPS_FIX_TYPE_3D_FIX.toShort()
        }
        else {
            msg.fix_type = GPS_FIX_TYPE.GPS_FIX_TYPE_NO_FIX.toShort()
        }
        return msg
    }

    fun msgLocalPositionNed(telemetry: TelemetryHandler, aircraft: AircraftHandler): MAVLinkMessage? {
        val telemetryData = telemetry.getTelemetryData() ?: return null
        val msg = msg_local_position_ned()
        msg.time_boot_ms = aircraft.systemBootTime
        msg.x = telemetryData.positionX
        msg.y = telemetryData.positionY
        msg.z = telemetryData.positionZ
        msg.vx = telemetryData.velocityX
        msg.vy = telemetryData.velocityY
        msg.vz = telemetryData.velocityZ
        return msg
    }

}
