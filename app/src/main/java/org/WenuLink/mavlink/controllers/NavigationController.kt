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
import com.MAVLink.enums.MAV_MISSION_RESULT
import com.MAVLink.enums.MAV_MISSION_TYPE
import com.MAVLink.enums.MAV_RESULT
import com.MAVLink.enums.MAV_SEVERITY
import io.getstream.log.taggedLogger
import kotlin.math.absoluteValue
import kotlin.math.roundToInt
import org.WenuLink.adapters.RequestMissionAction
import org.WenuLink.adapters.RequestStartMission
import org.WenuLink.adapters.WenuLinkCommand
import org.WenuLink.adapters.WenuLinkHandler
import org.WenuLink.adapters.mission.MissionNode
import org.WenuLink.adapters.mission.RepositionAction
import org.WenuLink.mavlink.MAVLinkClient
import org.WenuLink.mavlink.messages.DoRepositionCommandInt
import org.WenuLink.mavlink.messages.MessageUtils
import org.WenuLink.mavlink.messages.MissionStartCommandLong
import org.WenuLink.mavlink.messages.NavLandMissionItem
import org.WenuLink.mavlink.messages.NavTakeoffMissionItem
import org.WenuLink.mavlink.messages.NavWaypointMissionItem

/**
 * MAVLinkController class to deal with the mission service and related MAVLink messages.
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
    private var nextExpectedSeq = 0
    var wasRequested = false
        private set

    private val messageRegistry: Map<Int, (MAVLinkMessage) -> Unit> = mapOf(
        msg_mission_request_list.MAVLINK_MSG_ID_MISSION_REQUEST_LIST to { sendMissionCount() },
        msg_mission_count.MAVLINK_MSG_ID_MISSION_COUNT to ::createNewMission,
        msg_mission_item_int.MAVLINK_MSG_ID_MISSION_ITEM_INT to ::processMissionItem,
        msg_mission_request_int.MAVLINK_MSG_ID_MISSION_REQUEST_INT to ::sendMissionItem,
        msg_mission_clear_all.MAVLINK_MSG_ID_MISSION_CLEAR_ALL to { sendMissionClear() },
        msg_mission_ack.MAVLINK_MSG_ID_MISSION_ACK to ::processAck
    )

    private val commandLongRegistry: Map<Int, (msg_command_long) -> Unit> = mapOf(
        MAV_CMD.MAV_CMD_MISSION_START to ::missionStart
    )

    private val commandIntRegistry: Map<Int, (msg_command_int) -> Unit> = mapOf(
        MAV_CMD.MAV_CMD_DO_REPOSITION to ::processDoReposition
        // 192
    )

    override fun processMessage(msg: MAVLinkMessage): Boolean {
        messageRegistry[msg.msgid]?.invoke(msg) ?: return false
        return true
    }

    override fun processCommandLong(commandLongMsg: msg_command_long): Boolean {
        commandLongRegistry[commandLongMsg.command]?.invoke(commandLongMsg) ?: return false
        return true
    }

    override fun processCommandInt(commandIntMsg: msg_command_int): Boolean {
        commandIntRegistry[commandIntMsg.command]?.invoke(commandIntMsg) ?: return false
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

    fun node2MissionItemMsg(nIdx: Int): msg_mission_item_int {
        val node = handler.mission.getWaypointNode(nIdx)
        val coordinates = node.coordinates3D
        return when (node) {
            is MissionNode.Takeoff -> NavTakeoffMissionItem(
                latitude = coordinates.lat,
                longitude = coordinates.long,
                altitude = coordinates.alt
            ).toMavLink(nIdx)

            is MissionNode.Waypoint -> NavWaypointMissionItem(
                latitude = coordinates.lat,
                longitude = coordinates.long,
                altitude = coordinates.alt
            ).toMavLink(nIdx)

            is MissionNode.Land -> NavLandMissionItem(
                latitude = coordinates.lat,
                longitude = coordinates.long,
                altitude = coordinates.alt
            ).toMavLink(nIdx)
        }
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
            mission_type = MAV_MISSION_TYPE.MAV_MISSION_TYPE_MISSION.toShort()
            count = handler.mission.state.totalNodes()
            opaque_id = handler.mission.state.id.toLong()
            logger.d { "sendMissionCount: $count" }
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

        // TODO: Stop mission execution first if needed
        numberOfExpectedItems = missionMsg.count
        currentRetryTimes = 0
        nextExpectedSeq = 0

        // Only creates a new mission if has elements on it
        if (numberOfExpectedItems > 0) {
            // ask for the first item an iterate over count
            logger.d { "Creating new mission with ${missionMsg.count} items" }
            // Request first mission item...
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
        if (itemMsg.seq != nextExpectedSeq) {
            logger.e { "Received item #${itemMsg.seq} instead #$nextExpectedSeq, re-requesting..." }
            if (currentRetryTimes < maxRetryTimes) {
                requestMissionItem(nextExpectedSeq)
                currentRetryTimes += 1
            } else {
                logger.w { "Max re-requesting attempts reached" }
                sendAckAnswer(MAV_MISSION_RESULT.MAV_MISSION_INVALID_SEQUENCE)
            }
            return
        }

        // Store item and request next or upload the mission
        val accepted = handler.mission.addWaypointNode(itemMsg)
        if (!accepted) {
            logger.w { "Unsupported mission command: ${itemMsg.command}" }
            sendAckAnswer(MAV_MISSION_RESULT.MAV_MISSION_UNSUPPORTED)
            return
        }
        ackReceivedItem(nextExpectedSeq)
        nextExpectedSeq += 1

        if (itemMsg.seq < numberOfExpectedItems - 1) {
            // request next mission item
            requestMissionItem(nextExpectedSeq)
        } else {
            // reached the end of the mission items
            handler.mission.uploadWaypoints { result ->
                if (result.hasError) {
                    sendStatusText(
                        result.errorReason,
                        MAV_SEVERITY.MAV_SEVERITY_ERROR
                    )
                } else {
                    sendStatusText(
                        "Successful mission upload",
                        MAV_SEVERITY.MAV_SEVERITY_INFO
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

    fun sendStatusText(status: String, severity: Int) = client.sendMessage(
        msg_statustext().apply {
            logger.d { "sendStatusText" }
            text = status.toByteArray()
            this.severity = severity.toShort()
        }
    )

    fun missionStart(commandLongMsg: msg_command_long) {
        // TODO: Process init seq to custom first mission element
        val params = MissionStartCommandLong(commandLongMsg)

        client.sendMessage(
            MessageUtils.msgCommandAck(commandLongMsg.command, MAV_RESULT.MAV_RESULT_ACCEPTED)
        )
        handler.dispatchCommand(
            WenuLinkCommand.Request(
                RequestStartMission(
                    params.firstItem,
                    params.lastItem,
                    handler.aircraft.state.isArmed()
                )
            )
        ) { result ->
            logger.d { "missionStart: $result" }
        }
    }

    fun processDoReposition(commandIntMsg: msg_command_int) {
        val params = DoRepositionCommandInt(commandIntMsg)
        // mission flight speed is [-15, 15], so take its absolute
        val flightSpeed = if (params.speed == -1f) {
            logger.d { "Using default's mission flight speed: ${handler.mission.flightSpeed} m/s" }
            handler.mission.flightSpeed.absoluteValue
        } else {
            params.speed
        }
        // TODO: move this kind of conversion to something centralized, not sure if
        //  DoRepositionCommandInt should have it, since also happens with MissionHandler.flightSpeed
        //  demanding and easy to find of SDK constraints regard to commands and actions.
        // Ensure values [2, 15]
        val range = 2f..15f
        if (flightSpeed !in range) logger.w { "Clipped speed $flightSpeed to [$range]" }
        val repositionAction = RepositionAction.fromParameters(
            params.copy(speed = flightSpeed.coerceIn(range))
        )

        client.sendMessage(
            MessageUtils.msgCommandAck(commandIntMsg.command, MAV_RESULT.MAV_RESULT_ACCEPTED)
        )

        handler.dispatchCommand(
            WenuLinkCommand.Request(RequestMissionAction(repositionAction))
        ) { result ->
            logger.d { "processDoReposition: $result" }
        }
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
//            else {
//                logger.d { "Mission start error: $error" }
//            }
//        }
//    }

    fun msgMissionCurrent(): msg_mission_current = msg_mission_current().apply {
        seq = handler.mission.state.targetSequence
        total = handler.mission.state.assembler.size()
        mission_id = handler.mission.state.id.toLong()
        mission_state = handler.mission.state.mavlink.toShort()
        mission_mode = if (handler.mission.state.isActive()) 1 else 2
    }

    // TODO: start, pause, and resume procedures

    fun msgHomePosition(): MAVLinkMessage? = msg_home_position().apply {
        val telemetryData = handler.aircraft.currentTelemetry ?: return null
        val latVal = telemetryData.latitude ?: return null
        val lonVal = telemetryData.longitude ?: return null
        val altVal = telemetryData.altitude ?: return null

        latitude = MessageUtils.coordinateDJI2MAVLink(latVal)
        longitude = MessageUtils.coordinateDJI2MAVLink(lonVal)
        altitude = MessageUtils.altitudeDJI2MAVLink(altVal)
    }

    fun msgGlobalPositionInt(): MAVLinkMessage? = msg_global_position_int().apply {
        val telemetryData = handler.aircraft.currentTelemetry ?: return null
        val latVal = telemetryData.latitude ?: return null
        val lonVal = telemetryData.longitude ?: return null
        val altVal = telemetryData.altitude ?: return null
        val relAltVal = telemetryData.relativeAltitude ?: return null

        lat = MessageUtils.coordinateDJI2MAVLink(latVal)
        lon = MessageUtils.coordinateDJI2MAVLink(lonVal)
        alt = MessageUtils.altitudeDJI2MAVLink(altVal)
        // NOTE: Commented out this field, because relative_alt seems to be intended for
        // altitude above the current terrain, but DJI reports altitude above home point.
        // Mavlink: Millimeters above ground (unspecified: presumably above home point?)
        // DJI: relative altitude of the aircraft relative to take off location, measured by
        // barometer, in meters.
        relative_alt = MessageUtils.altitudeDJI2MAVLink(relAltVal)
        vx = (telemetryData.velocityX * 100).roundToInt().toShort()
        vy = (telemetryData.velocityY * 100).roundToInt().toShort()
        vz = (telemetryData.velocityZ * 100).roundToInt().toShort()
        var yaw = telemetryData.yaw
        if (yaw < 0) yaw += 360
        hdg = (yaw * 100).roundToInt()
//        client.sendMessage(msg)
    }

    fun msgRawGPSInt(): MAVLinkMessage? = msg_gps_raw_int().apply {
        if (handler.aircraft.telemetry.isSimulationActive) {
            fix_type = GPS_FIX_TYPE.GPS_FIX_TYPE_2D_FIX.toShort()
            return@apply
        }

        val telemetryData = handler.aircraft.currentTelemetry ?: return null
        val latVal = telemetryData.latitude ?: return null
        val lonVal = telemetryData.longitude ?: return null

        time_usec = MessageUtils.getMicroTime()
        lat = MessageUtils.coordinateDJI2MAVLink(latVal)
        lon = MessageUtils.coordinateDJI2MAVLink(lonVal)
        satellites_visible = telemetryData.satelliteCount.toShort()
        fix_type = telemetryData.gpsFixType.toShort()
    }

    fun msgLocalPositionNed(): MAVLinkMessage? = msg_local_position_ned().apply {
        val telemetryData = handler.aircraft.currentTelemetry ?: return null

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
