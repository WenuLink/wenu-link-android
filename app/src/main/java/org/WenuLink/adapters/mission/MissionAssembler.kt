package org.WenuLink.adapters.mission

import com.MAVLink.common.msg_mission_item_int
import com.MAVLink.enums.MAV_CMD
import com.MAVLink.enums.MAV_FRAME
import io.getstream.log.taggedLogger
import org.WenuLink.adapters.aircraft.Coordinates3D
import org.WenuLink.mavlink.messages.ConditionYawMessage
import org.WenuLink.mavlink.messages.ImageStartCaptureMessage
import org.WenuLink.mavlink.messages.NavDelayMessage
import org.WenuLink.mavlink.messages.NavTakeoffMissionItem
import org.WenuLink.mavlink.messages.NavWaypointMissionItem

class MissionAssembler(private val id: Int) {

    private val logger by taggedLogger(MissionAssembler::class.java.simpleName)
    private val nodes = mutableListOf<MissionNode>()
    private var rtlWhenFinish = false
    var timestamp = System.currentTimeMillis()
        private set
    var nWaypoints = 0
        private set

    fun getNode(nId: Int): MissionNode = nodes[nId]

    fun hasNodes() = !nodes.isEmpty()

    fun size(): Int = nodes.size

    fun reset() {
        nodes.clear()
        rtlWhenFinish = false
        nWaypoints = 0
    }

    fun addHome(coordinates: Coordinates3D): Boolean = nodes.add(MissionNode.Home(coordinates))

    fun addTakeoff(coordinates: Coordinates3D): Boolean =
        nodes.add(MissionNode.Takeoff(coordinates))

    fun addWaypoint(coordinates: Coordinates3D): Boolean =
        nodes.add(MissionNode.Waypoint(coordinates)).also {
            nWaypoints += 1
        }

    fun addActionToLast(missionAction: MissionActionCommand) {
        (nodes.lastOrNull() as? MissionNode.Waypoint)
            ?.actions
            ?.add(missionAction)
    }

    fun setRTLWhenFinish() {
        rtlWhenFinish = true
    }

    fun build(): AssembledMission = AssembledMission(nodes.toList(), nWaypoints, rtlWhenFinish)

    fun addWaypointNode(itemMsg: msg_mission_item_int): ItemAssemblyResult {
        // ArduPilot mission protocol: seq 0 is always the home location, never a flight item.
        // https://mavlink.io/en/services/mission.html#flight-plan-missions
        if (itemMsg.seq == 0) return assembleHomeNode(itemMsg)

        when (itemMsg.command) {
            MAV_CMD.MAV_CMD_NAV_TAKEOFF -> return assembleTakeoffNode(itemMsg)

            MAV_CMD.MAV_CMD_NAV_WAYPOINT -> return assembleWaypointNode(itemMsg)

            MAV_CMD.MAV_CMD_NAV_DELAY,
            MAV_CMD.MAV_CMD_CONDITION_DELAY -> addActionToLast(
                DelayAction.fromParameters(NavDelayMessage(itemMsg))
            )

            MAV_CMD.MAV_CMD_CONDITION_YAW -> addActionToLast(
                RotateAction.fromParameters(ConditionYawMessage(itemMsg))
            )

            MAV_CMD.MAV_CMD_IMAGE_START_CAPTURE -> addActionToLast(
                PhotoAction.fromParameters(ImageStartCaptureMessage(itemMsg))
            )

            MAV_CMD.MAV_CMD_IMAGE_STOP_CAPTURE -> addActionToLast(StopPhotoAction)

            MAV_CMD.MAV_CMD_VIDEO_START_CAPTURE -> addActionToLast(VideoAction())

            MAV_CMD.MAV_CMD_VIDEO_STOP_CAPTURE -> addActionToLast(StopVideoAction)

            MAV_CMD.MAV_CMD_NAV_RETURN_TO_LAUNCH -> setRTLWhenFinish()

            else -> return ItemAssemblyResult.UnsupportedCommand
        }

        return ItemAssemblyResult.Accepted
    }

    private fun assembleHomeNode(itemMsg: msg_mission_item_int): ItemAssemblyResult {
        // QGC transmits home as NAV_WAYPOINT in MAV_FRAME_GLOBAL (AMSL altitude)
        if (itemMsg.command != MAV_CMD.MAV_CMD_NAV_WAYPOINT) {
            logger.w { "Item 0 is not a home waypoint (command=${itemMsg.command})" }
            return ItemAssemblyResult.UnsupportedCommand
        }

        val params = NavWaypointMissionItem(itemMsg)
        addHome(Coordinates3D(params.latitude, params.longitude, params.altitude))

        logger.d { "Home: (${params.latitude}, ${params.longitude}) AMSL ${params.altitude}" }
        return ItemAssemblyResult.Accepted
    }

    private fun hasSupportedFrame(itemMsg: msg_mission_item_int): Boolean =
        when (itemMsg.frame.toInt()) {
            MAV_FRAME.MAV_FRAME_GLOBAL_RELATIVE_ALT,
            MAV_FRAME.MAV_FRAME_GLOBAL_RELATIVE_ALT_INT -> true

            else -> false
        }

    private fun assembleTakeoffNode(itemMsg: msg_mission_item_int): ItemAssemblyResult {
        if (!hasSupportedFrame(itemMsg)) return ItemAssemblyResult.UnsupportedFrame

        val params = NavTakeoffMissionItem(itemMsg)
        // If there is no coordinates on takeoff node, replace with the first node
        // assumed as Home given seq == 0
        val home = nodes.firstOrNull() as? MissionNode.Home
            ?: return ItemAssemblyResult.UnsupportedCommand
        val coordinates = if (params.latitude == 0.0 && params.longitude == 0.0) {
            Coordinates3D(home.coordinates3D.lat, home.coordinates3D.long, params.altitude)
        } else {
            Coordinates3D(params.latitude, params.longitude, params.altitude)
        }

        addTakeoff(coordinates)
        logger.d { "Takeoff: (${coordinates.lat}, ${coordinates.long}) ALT ${params.altitude}" }
        return ItemAssemblyResult.Accepted
    }

    private fun assembleWaypointNode(itemMsg: msg_mission_item_int): ItemAssemblyResult {
        if (!hasSupportedFrame(itemMsg)) return ItemAssemblyResult.UnsupportedFrame
        val params = NavWaypointMissionItem(itemMsg)
        val coordinates = Coordinates3D(params.latitude, params.longitude, params.altitude)

        addWaypoint(coordinates)

        // Delay (seconds)
        if (params.holdTimeSec > 0f) {
            addActionToLast(DelayAction((params.holdTimeSec * 1000).toLong()))
        }

        // Yaw
        if (!params.yaw.isNaN()) {
            addActionToLast(RotateAction(params.yaw))
        }

        logger.d { "Waypoint: ($coordinates) (Yaw=${params.yaw}°) (Delay=${params.holdTimeSec}s)" }

        return ItemAssemblyResult.Accepted
    }
}
