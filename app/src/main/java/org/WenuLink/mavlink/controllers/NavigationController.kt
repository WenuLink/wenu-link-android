package org.WenuLink.mavlink.controllers

import com.MAVLink.Messages.MAVLinkMessage
import com.MAVLink.common.msg_command_int
import com.MAVLink.common.msg_command_long
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
import io.getstream.log.taggedLogger
import kotlin.math.roundToInt
import org.WenuLink.adapters.MessageUtils
import org.WenuLink.adapters.RequestMissionAction
import org.WenuLink.adapters.RequestStartMission
import org.WenuLink.adapters.WenuLinkCommand
import org.WenuLink.adapters.WenuLinkHandler
import org.WenuLink.adapters.mission.MissionNode
import org.WenuLink.adapters.mission.RepositionAction
import org.WenuLink.mavlink.MAVLinkClient

/**
 * MAVLinkController class to deal with the handler.mission service and related MAVLink messages.
 *
 * https://mavlink.io/en/services/handler.mission.html
 *
 * https://developer.dji.com/api-reference/android-api/Components/Missions/DJIWaypointMissionOperator.html
 * https://developer.dji.com/api-reference/android-api/Components/Missions/DJIMissionControl.html
 *
 */
class NavigationController(
    override val client: MAVLinkClient,
    override val handler: WenuLinkHandler
) : IController {
    private val logger by taggedLogger(NavigationController::class.java.simpleName)

    private var maxRetryTimes = 5
    private var currentRetryTimes = 0
    private var numberOfExpectedItems = -1
    var wasRequested = false
        private set

    override fun processMessage(msg: MAVLinkMessage): Boolean {
        when (msg.msgid) {
            msg_mission_request_list.MAVLINK_MSG_ID_MISSION_REQUEST_LIST -> sendMissionCount()
            msg_mission_count.MAVLINK_MSG_ID_MISSION_COUNT -> createNewMission(msg)
            msg_mission_item_int.MAVLINK_MSG_ID_MISSION_ITEM_INT -> processMissionItem(msg)
            msg_mission_request_int.MAVLINK_MSG_ID_MISSION_REQUEST_INT -> sendMissionItem(msg)
            msg_mission_clear_all.MAVLINK_MSG_ID_MISSION_CLEAR_ALL -> sendMissionClear()
            msg_mission_ack.MAVLINK_MSG_ID_MISSION_ACK -> processAck(msg)
            else -> return false
        }
        return true
    }

    override fun processCommandLong(commandLongMsg: msg_command_long): Boolean {
        when (commandLongMsg.command) {
            MAV_CMD.MAV_CMD_MISSION_START -> missionStart(commandLongMsg)
            else -> return false
        }
        return true
    }

    override fun createMessage(messageID: Int): MAVLinkMessage? = when (messageID) {
        msg_mission_current.MAVLINK_MSG_ID_MISSION_CURRENT -> msgMissionCurrent()
        msg_home_position.MAVLINK_MSG_ID_HOME_POSITION -> msgHomePosition()
        msg_gps_raw_int.MAVLINK_MSG_ID_GPS_RAW_INT -> msgRawGPSInt()
        msg_global_position_int.MAVLINK_MSG_ID_GLOBAL_POSITION_INT -> msgGlobalPositionInt()
        msg_local_position_ned.MAVLINK_MSG_ID_LOCAL_POSITION_NED -> msgLocalPositionNed()
        msg_gps_global_origin.MAVLINK_MSG_ID_GPS_GLOBAL_ORIGIN -> msgGpsGlobalOrigin()
        else -> null
    }

    override fun processCommandInt(commandIntMsg: msg_command_int): Boolean {
        when (commandIntMsg.command) {
            MAV_CMD.MAV_CMD_DO_REPOSITION -> processDoReposition(commandIntMsg)

            // 192
            else -> return false
        }
        return true
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

    fun node2MissionItemMsg(nIdx: Int): msg_mission_item_int {
        val node = handler.mission.getWaypointNode(nIdx)
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

    fun sendAckAnswer(type: Int) = client.sendMessage(
        msg_mission_ack().apply {
            this.type = type.toShort()
            opaque_id = handler.mission.state.id.toLong()
        }
    )

    fun processAck(msg: MAVLinkMessage) {
        val ackMsg = msg as msg_mission_ack
        wasRequested = true
        logger.d { "ACK type: ${ackMsg.type} missionType ${ackMsg.mission_type}" }
    }

    fun sendMissionCount() = client.sendMessage(
        msg_mission_count().apply {
            logger.d { "sendMissionCount: $count" }
            mission_type = MAV_MISSION_TYPE.MAV_MISSION_TYPE_MISSION.toShort()
            count = handler.mission.state.totalNodes()
            opaque_id = handler.mission.state.id.toLong()
        }
    )

    fun sendMissionItem(msg: MAVLinkMessage) {
        val itemMsg = msg as msg_mission_request_int
        val idx = itemMsg.seq
        logger.d { "sendMissionItem #$idx" }
        if (handler.mission.hasWaypointNodes()) {
            val itemMsg = node2MissionItemMsg(idx)
            client.sendMessage(itemMsg)
        }
    }

    fun sendMissionClear() {
        handler.missionClear()
        sendAckAnswer(MAV_MISSION_RESULT.MAV_MISSION_ACCEPTED)
    }

    fun requestMissionItem(seq: Int) = client.sendMessage(
        msg_mission_request_int().apply {
            logger.d { "requestMissionItem #$seq" }
            this.seq = seq
            mission_type = MAV_MISSION_TYPE.MAV_MISSION_TYPE_MISSION.toShort()
        }
    )

    fun createNewMission(msg: MAVLinkMessage) {
        logger.d { "createNewMission" }
        val missionMsg = msg as msg_mission_count

        if (!handler.mission.state.canCreateMission()) return

        // TODO: Stop handler.mission execution first if needed
        numberOfExpectedItems = missionMsg.count

        // Only creates a new handler.mission if has elements on it
        if (numberOfExpectedItems > 0) {
            // ask for the first item an iterate over count
            logger.d { "Creating new handler.mission with ${missionMsg.count} items" }
            // Request first handler.mission item...
            requestMissionItem(0)
            // TODO: timeout waiting start?
        }
    }

    fun ackReceivedItem(sequence: Int) = client.sendMessage(
        msg_mission_item_reached().apply {
            logger.d { "ackReceivedItem #$sequence" }
            seq = sequence
        }
    )

    fun processMissionItem(msg: MAVLinkMessage) {
        logger.d { "processMissionItem" }
        val itemMsg = msg as msg_mission_item_int
        logger.d { "\t$itemMsg" }
        // Validate sequence
        val expectedSeq = handler.mission.state.totalNodes()
        if (itemMsg.seq != expectedSeq) {
            logger.e { "Received item #${itemMsg.seq} instead #$expectedSeq, re-requesting..." }
            if (currentRetryTimes < maxRetryTimes) {
                requestMissionItem(expectedSeq)
                currentRetryTimes += 1
            } else {
                logger.w { "Max re-requesting attempts reached" }
            }

            return
        }
        // Store item and request next or upload the handler.mission
        handler.mission.addWaypointNode(itemMsg)
        ackReceivedItem(expectedSeq)

        if (itemMsg.seq < numberOfExpectedItems - 1) {
            // request next handler.mission item
            requestMissionItem(expectedSeq + 1)
        } else {
            // reached the end of the handler.mission items
            handler.mission.uploadWaypoints { result ->
                if (result.hasError) {
                    sendStatusText(
                        result.errorReason,
                        MAV_SEVERITY.MAV_SEVERITY_ERROR
                    )
                }
            }
            sendAckAnswer(MAV_MISSION_RESULT.MAV_MISSION_ACCEPTED)
        }
    }

    fun sendMissionCurrent(sequence: Int) = client.sendMessage(
        msgMissionCurrent().apply {
            logger.d { "sendCurrentMission" }
            seq = sequence
        }
    )

    fun sendStatusText(error: String, severity: Int) = client.sendMessage(
        msg_statustext().apply {
            logger.d { "sendStatusText" }
            text = error.toByteArray()
            this.severity = severity.toShort()
        }
    )

    fun missionStart(commandLongMsg: msg_command_long) {
        // TODO: Process init seq to custom first handler.mission element
        handler.dispatchCommand(
            WenuLinkCommand.Request(
                RequestStartMission(
                    commandLongMsg.param1.toInt(),
                    commandLongMsg.param2.toInt()
                )
            )
        )

        client.sendMessage(
            MessageUtils.msgCommandAck(
                MAV_CMD.MAV_CMD_MISSION_START,
                MAV_RESULT.MAV_RESULT_ACCEPTED
            )
        )
    }

    fun processDoReposition(commandIntMsg: msg_command_int) {
        handler.dispatchCommand(
            WenuLinkCommand.Request(
                RequestMissionAction(RepositionAction.fromCommandInt(commandIntMsg))
            )
        )

        client.sendMessage(
            MessageUtils.msgCommandAck(
                MAV_CMD.MAV_CMD_DO_REPOSITION,
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
//        // TODO; stop first if must, update currentItemSequence, and current handler.mission in aircraft(?)
//
//        currentItemSequence = if (requestedSeq == -1 || reset) 0 else requestedSeq
//        startMission { error ->
//            if (error == null) {
//                logger.d { "Mission started" }
//                sendCurrentMission(currentItemSequence)
//            }
//            else {
//                logger.d { "Mission start error: $error" }
//            }
//        }
//    }

    fun msgMissionCurrent(): msg_mission_current = msg_mission_current().apply {
        seq = handler.mission.state.currentSequence ?: 0
        mission_id = handler.mission.state.id.toLong()
        mission_state = handler.mission.state.mavlink.toShort()
    }

    // TODO: start, pause, and resume procedures

    fun msgHomePosition(): MAVLinkMessage? = msg_home_position().apply {
        val coordinates = handler.aircraft.state.homeCoordinates ?: return null

        latitude = MessageUtils.coordinateDJI2MAVLink(coordinates.lat)
        longitude = MessageUtils.coordinateDJI2MAVLink(coordinates.long)
        altitude = MessageUtils.altitudeDJI2MAVLink(coordinates.alt)
    }

    fun msgGlobalPositionInt(): MAVLinkMessage? = msg_global_position_int().apply {
        val telemetryData = handler.aircraft.telemetry.getData() ?: return null

        lat = MessageUtils.coordinateDJI2MAVLink(telemetryData.latitude)
        lon = MessageUtils.coordinateDJI2MAVLink(telemetryData.longitude)
        alt = MessageUtils.altitudeDJI2MAVLink(telemetryData.altitude)
        // NOTE: Commented out this field, because relative_alt seems to be intended for
        // altitude above the current terrain, but DJI reports altitude above home point.
        // Mavlink: Millimeters above ground (unspecified: presumably above home point?)
        // DJI: relative altitude of the aircraft relative to take off location, measured by
        // barometer, in meters.
        relative_alt = MessageUtils.altitudeDJI2MAVLink(telemetryData.relativeAltitude)
        vx = (telemetryData.velocityX * 100).roundToInt().toShort()
        vy = (telemetryData.velocityY * 100).roundToInt().toShort()
        vz = (telemetryData.velocityZ * 100).roundToInt().toShort()
        var yaw = telemetryData.yaw
        if (yaw < 0) yaw += 360
        hdg = (yaw * 100).roundToInt()
//        client.sendMessage(msg)
    }

    fun msgRawGPSInt(): MAVLinkMessage? = msg_gps_raw_int().apply {
        val telemetryData = handler.aircraft.telemetry.getData() ?: return null

        if (handler.aircraft.telemetry.isSimulationActive) {
            fix_type = GPS_FIX_TYPE.GPS_FIX_TYPE_NO_GPS.toShort()
            return@apply
        }

        val gpsLevel = telemetryData.gpsLevel.indexOfFirst { it }

        time_usec = MessageUtils.getMicroTime()
        lat = MessageUtils.coordinateDJI2MAVLink(telemetryData.latitude)
        lon = MessageUtils.coordinateDJI2MAVLink(telemetryData.longitude)
        satellites_visible = telemetryData.satelliteCount.toShort()

        // DJI reports signal quality as a level 0-11; mapped to Mavlink  fix types below.
        fix_type = when (gpsLevel) {
            0, 1 -> GPS_FIX_TYPE.GPS_FIX_TYPE_NO_FIX.toShort()
            2 -> GPS_FIX_TYPE.GPS_FIX_TYPE_2D_FIX.toShort()
            3, 4, 5 -> GPS_FIX_TYPE.GPS_FIX_TYPE_3D_FIX.toShort()
            else -> GPS_FIX_TYPE.GPS_FIX_TYPE_NO_FIX.toShort()
        }
    }

    fun msgLocalPositionNed(): MAVLinkMessage? = msg_local_position_ned().apply {
        val telemetryData = handler.aircraft.telemetry.getData() ?: return null

        time_boot_ms = handler.systemBootTime
        x = telemetryData.positionX
        y = telemetryData.positionY
        z = telemetryData.positionZ
        vx = telemetryData.velocityX
        vy = telemetryData.velocityY
        vz = telemetryData.velocityZ
    }

    fun msgGpsGlobalOrigin(): MAVLinkMessage? = msg_gps_global_origin().apply {
        val homeLoc = handler.aircraft.state.homeCoordinates ?: return null

        latitude = MessageUtils.coordinateDJI2MAVLink(homeLoc.lat)
        longitude = MessageUtils.coordinateDJI2MAVLink(homeLoc.long)
        altitude = MessageUtils.altitudeDJI2MAVLink(homeLoc.alt)
        time_usec = MessageUtils.getMicroTime()
    }
}
