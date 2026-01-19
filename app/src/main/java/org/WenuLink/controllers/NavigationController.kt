package org.WenuLink.controllers

import com.MAVLink.Messages.MAVLinkMessage
import com.MAVLink.common.msg_command_int
import com.MAVLink.common.msg_command_long
import com.MAVLink.common.msg_extended_sys_state
import com.MAVLink.common.msg_global_position_int
import com.MAVLink.common.msg_gps_global_origin
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
import com.MAVLink.enums.MAV_RESULT
import com.MAVLink.enums.MAV_SEVERITY
import com.MAVLink.enums.MAV_VTOL_STATE
import io.getstream.log.taggedLogger
import kotlinx.coroutines.CoroutineScope
import org.WenuLink.adapters.AircraftHandler
import org.WenuLink.adapters.TelemetryHandler
import org.WenuLink.adapters.MessageUtils
import org.WenuLink.adapters.MissionHandler
import org.WenuLink.adapters.mission.MissionNode
import org.WenuLink.mavlink.MAVLinkClient
import org.WenuLink.sdk.MissionManager
import kotlin.getValue

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
    private var maxRetryTimes = 5
    private var currentRetryTimes = 0
    private var numberOfExpectedItems = -1
    private var mission = MissionHandler.getInstance()
    var wasRequested = false
        private set

    override fun processMessage(msg: MAVLinkMessage, aircraft: AircraftHandler): Boolean {
        var processed = true
        when (msg.msgid) {
            msg_mission_request_list.MAVLINK_MSG_ID_MISSION_REQUEST_LIST -> sendMissionCount()
            msg_mission_count.MAVLINK_MSG_ID_MISSION_COUNT -> createNewMission(msg)
            msg_mission_item_int.MAVLINK_MSG_ID_MISSION_ITEM_INT -> processMissionItem(msg)
            msg_mission_request_int.MAVLINK_MSG_ID_MISSION_REQUEST_INT -> sendMissionItem(msg)
            msg_mission_clear_all.MAVLINK_MSG_ID_MISSION_CLEAR_ALL -> sendMissionClear()
            msg_mission_ack.MAVLINK_MSG_ID_MISSION_ACK -> processAck(msg)
            else -> processed = false
        }
        return processed
    }

    override fun processCommandLong(
        commandLongMsg: msg_command_long,
        aircraft: AircraftHandler,
        serviceScope: CoroutineScope
    ): Boolean {
        var processed = true
        when (commandLongMsg.command) {
            MAV_CMD.MAV_CMD_MISSION_START -> missionStart(commandLongMsg)
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
            msg_gps_global_origin.MAVLINK_MSG_ID_GPS_GLOBAL_ORIGIN -> msgGpsGlobalOrigin(aircraft)
            else -> null
        }
    }

    override fun processCommandInt(
        commandIntMsg: msg_command_int,
        aircraft: AircraftHandler,
        serviceScope: CoroutineScope
    ): Boolean {
        var processed = true
        when (commandIntMsg.command) {
            MAV_CMD.MAV_CMD_DO_REPOSITION -> logger.d { "TODO: $commandIntMsg" } // 192
            else -> processed = false
        }
        return processed
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
        logger.d { "Creating MissionItem: $msg" }
        return msg
    }

    fun node2missionItemMsg(nIdx: Int): msg_mission_item_int {
        val node = mission.getWaypointNode(nIdx)
        val coordinates = node.coordinates3D
        val command = when (node) {
            is MissionNode.Takeoff -> MAV_CMD.MAV_CMD_NAV_TAKEOFF
            is MissionNode.Waypoint -> MAV_CMD.MAV_CMD_NAV_WAYPOINT
            is MissionNode.Land -> MAV_CMD.MAV_CMD_NAV_LAND
        }

        return createMissionItemMsg(
            nIdx,
            coordinates.lat.toInt(),
            coordinates.long.toInt(),
            coordinates.alt,
            command
        )
    }

    fun sendAckAnswer(type: Int) {
        val msg = msg_mission_ack()
        msg.type = type.toShort()
        msg.opaque_id = mission.currentId
        client.sendMessage(msg)
    }

    fun processAck(msg: MAVLinkMessage) {
        val ackMsg = msg as msg_mission_ack
        wasRequested = true
        logger.d { "ACK type: ${ackMsg.type} missionType ${ackMsg.mission_type}" }
    }

    fun sendMissionCount() {
        val msg = msg_mission_count()
        msg.mission_type = MAV_MISSION_TYPE.MAV_MISSION_TYPE_MISSION.toShort()
        msg.count = mission.totalNodes
        msg.opaque_id = mission.currentId
        logger.d { "sendMissionCount: ${msg.count}" }
        client.sendMessage(msg)
    }

    fun sendMissionItem(msg: MAVLinkMessage) {
        val itemMsg = msg as msg_mission_request_int
        val idx = itemMsg.seq
        logger.d { "sendMissionItem #$idx" }
        if (mission.hasWaypointNodes()){
            val itemMsg = node2missionItemMsg(idx)
            client.sendMessage(itemMsg)
        }
    }

    fun sendMissionClear() {
        mission.reset()
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

        logger.d { "Mission state: ${MissionManager.getWaypointMissionState()}" }
        if (MissionManager.getWaypointMissionState().name != "READY_TO_UPLOAD") return

        // TODO: Stop mission execution first if needed
        numberOfExpectedItems = missionMsg.count

        // Only creates a new mission if has elements on it
        if (numberOfExpectedItems > 0) {
            // ask for the first item an iterate over count
            logger.d { "Creating new mission with ${missionMsg.count} items" }
            // Request first mission item...
            requestMissionItem(0)
            // TODO: timeout waiting start?
        }
    }

    fun ackReceivedItem(sequence: Int) {
        logger.d { "ackReceivedItem #$sequence" }
        val msg = msg_mission_item_reached()
        msg.seq = sequence
        client.sendMessage(msg)
    }

    fun processMissionItem(msg: MAVLinkMessage) {
        logger.d { "processMissionItem" }
        val itemMsg = msg as msg_mission_item_int
        logger.d { "\t$itemMsg" }
        // Validate sequence
        val expectedSeq = mission.totalNodes
        if (itemMsg.seq != expectedSeq) {
            logger.e { "Received item #${itemMsg.seq} instead #${expectedSeq}, re-requesting..." }
            if (currentRetryTimes < maxRetryTimes) {
                requestMissionItem(expectedSeq)
                currentRetryTimes += 1
            } else logger.w { "Max re-requesting attempts reached" }

            return
        }
        // Store item and request next or upload the mission
        mission.addWaypointNode(itemMsg)
        ackReceivedItem(expectedSeq)

        if (itemMsg.seq < numberOfExpectedItems - 1) {
            // request next mission item
            requestMissionItem(expectedSeq + 1)
        } else {
            // reached the end of the mission items
            mission.upload{ error ->
                if (error != null) sendStatusText(error, MAV_SEVERITY.MAV_SEVERITY_ERROR)
            }
            sendAckAnswer(MAV_MISSION_RESULT.MAV_MISSION_ACCEPTED)
        }
    }

    fun sendMissionCurrent(sequence: Int) {
        logger.d { "sendCurrentMission" }
        val msg = msgMissionCurrent()
        msg.seq = sequence
        client.sendMessage(msg)
    }

    fun sendStatusText(error: String, severity: Int) {
        logger.d { "sendStatusText" }
        val msg = msg_statustext()
        msg.text = error.toByteArray()
        msg.severity = severity.toShort()
        client.sendMessage(msg)
    }

    fun missionStart(msg: MAVLinkMessage) {
        mission.start()
        client.sendMessage(
            MessageUtils.msgCommandAck(
                MAV_CMD.MAV_CMD_MISSION_START,
                MAV_RESULT.MAV_RESULT_ACCEPTED
            )
        )
    }

//    fun setCurrentMissionItem(msg: msg_mission_item_int, aircraft: AircraftHandler) {
//        logger.d { "setCurrentMissionItem" }
//
//        val reset = msg.param2 == 1f
//        val requestedSeq = msg.param1.toInt()
//
//        // TODO; stop first if must, update currentItemSequence, and current mission in aircraft(?)
//
//        currentItemSequence = if (requestedSeq == -1 || reset) 0 else requestedSeq
//        startMission { error ->
//            if (error == null) {
//                logger.d { "Mission started" }
//                sendCurrentMission(currentItemSequence)
//            }
//            if (error != null) logger.d { "Mission start error: $error" }
//        }
//    }

    fun msgMissionCurrent(): msg_mission_current {
        val msg = msg_mission_current()
        msg.seq = mission.currentSequence
        msg.mission_id = mission.currentId
        msg.mission_state = mission.currentState.toShort()
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
        msg.landed_state = aircraft.state.landed.toShort()
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

    fun msgGpsGlobalOrigin(aircraft: AircraftHandler): MAVLinkMessage? {
        val homeLoc = aircraft.getHomePosition() ?: return null
        val msg = msg_gps_global_origin()
        msg.latitude = MessageUtils.coordinateDJI2MAVLink(homeLoc.lat)
        msg.longitude = MessageUtils.coordinateDJI2MAVLink(homeLoc.long)
        msg.altitude = MessageUtils.altitudeDJI2MAVLink(homeLoc.alt)
        msg.time_usec = MessageUtils.getMicroTime()
        return msg
    }

}
