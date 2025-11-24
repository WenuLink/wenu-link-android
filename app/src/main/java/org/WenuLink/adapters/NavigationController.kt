package org.WenuLink.adapters

import android.util.Log
import com.MAVLink.Messages.MAVLinkMessage
import com.MAVLink.common.msg_mission_ack
import com.MAVLink.common.msg_mission_clear_all
import com.MAVLink.common.msg_mission_count
import com.MAVLink.common.msg_mission_current
import com.MAVLink.common.msg_mission_item_int
import com.MAVLink.common.msg_mission_item_reached
import com.MAVLink.common.msg_mission_request
import com.MAVLink.common.msg_mission_request_int
import com.MAVLink.common.msg_mission_request_list
import com.MAVLink.common.msg_statustext
import com.MAVLink.enums.MAV_CMD
import com.MAVLink.enums.MAV_FRAME
import com.MAVLink.enums.MAV_MISSION_RESULT
import com.MAVLink.enums.MAV_MISSION_TYPE
import com.MAVLink.enums.MAV_SEVERITY
import com.MAVLink.enums.MISSION_STATE
import org.WenuLink.mavlink.MAVLinkClient
import org.WenuLink.mavlink.MAVLinkController
import org.WenuLink.sdk.MissionManager
import kotlin.math.roundToInt

/**
 * MAVLinkController class to deal with the mission service and related MAVLink messages.
 * https://mavlink.io/en/services/mission.html
 */
class NavigationController (
    private var client: MAVLinkClient,
) : MAVLinkController {
    private val TAG: String = NavigationController::class.java.simpleName
    private val missionItems = ArrayList<msg_mission_item_int>()
    private var isReceivingMission = false
    private var isTakeoffReceived = false
    private var numberOfExpectedItems = -1
    private var numberOfStoredItems = 0
    private var flightSpeed: Float = 5f
    private var maxRetryTimes = 5
    private var currentRetryTimes = 0
    // TODO: implement missionStart and missionStop
    // TODO: probably should need fix threading and/or execution scope

    init {
        populateMissionList()
    }

    fun getMissionId(): Long {
        return if (missionItems.isNotEmpty() && !isReceivingMission) {
            202512 // random number
        } else 0
    }

    override fun processMessage(msg: MAVLinkMessage) {
        when (msg.msgid) {
            msg_mission_count.MAVLINK_MSG_ID_MISSION_COUNT -> createNewMission(msg as msg_mission_count)
            msg_mission_item_int.MAVLINK_MSG_ID_MISSION_ITEM_INT -> processMissionItem(msg as msg_mission_item_int)
            msg_mission_request_list.MAVLINK_MSG_ID_MISSION_REQUEST_LIST -> sendMissionCount()
            msg_mission_request_int.MAVLINK_MSG_ID_MISSION_REQUEST_INT -> sendMissionItem(msg as msg_mission_request)
            msg_mission_clear_all.MAVLINK_MSG_ID_MISSION_CLEAR_ALL -> flushMissionItemList()
            msg_mission_ack.MAVLINK_MSG_ID_MISSION_ACK -> processAck(msg as msg_mission_ack)
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
        sendAckAnswer(MAV_MISSION_RESULT.MAV_MISSION_ACCEPTED)
    }

    private fun coordinateDJI2MAVLink(value: Double): Int {
        return (10_000_000 * value).roundToInt()
    }

    private fun coordinateMAVLink2DJI(value: Int): Double {
        return value / 10_000_000.0
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
        var itemSeq = 0
        for (wp in currentMission.waypointList) {
            val coordX = coordinateDJI2MAVLink(wp.coordinate.latitude)
            val coordY = coordinateDJI2MAVLink(wp.coordinate.longitude)
            val coordZ = wp.altitude
            // Assign the correct command
            if (itemSeq == 0) {
                // Assume the first mission point is a takeoff...
                Log.d(TAG, "Mission Command : Takeoff")
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
                Log.d(TAG, "Mission Command : Waypoint")
                appendMissionItem(createMissionItemMsg(itemSeq, coordX, coordY, coordZ))
                itemSeq += 1

                for (action in wp.waypointActions) {
                    Log.d(TAG, "Waypoint Action : $action")
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

    fun processAck(msg: msg_mission_ack) {
        Log.d(TAG, "ACK type: ${msg.type} missionType ${msg.mission_type}")
    }

    fun sendMissionCount() {
        val msg = msg_mission_count()
        msg.mission_type = MAV_MISSION_TYPE.MAV_MISSION_TYPE_MISSION.toShort()
        msg.count = numberOfStoredItems
        msg.opaque_id = getMissionId()
        Log.d(TAG, "Mission Count: ${msg.count}")
        client.sendMessage(msg)
    }

    fun sendMissionItem(msg: msg_mission_request) {
        val idx = msg.seq
        if (missionItems.isNotEmpty())
            client.sendMessage(missionItems[idx])
    }

    fun requestMissionItem(seq: Int) {
        Log.d(TAG, "Requesting mission item #$seq...")
        val msg = msg_mission_request_int()
        msg.seq = seq
        msg.mission_type = MAV_MISSION_TYPE.MAV_MISSION_TYPE_MISSION.toShort()
        client.sendMessage(msg)
    }

    fun createNewMission(msg: msg_mission_count) {
        // Is this message to this system...
        if (!client.isTargetSystem(msg.target_system)) {
            return
        }

        // Stop mission execution first
        MissionManager.runMission(false) { error ->
            if (error != null) {
                Log.e(TAG, "Unable to create new mission, current mission didn't stop.")
            } else {
                // flush mission items
                flushMissionItemList()
                isReceivingMission = true
                isTakeoffReceived = false
                numberOfExpectedItems = msg.count - 1

                // Only creates a new mission if has elements on it
                if (numberOfExpectedItems > 0) {
                    // ask for the first item an iterate over count
                    Log.d(TAG, "Creating new mission with ${msg.count} items")

                    // Request first mission item...
                    requestMissionItem(0)
                    // TODO: timeout waiting start?
                }
            }
        }
    }

    fun processTakeoff(altitude: Float) {
        if (isReceivingMission && !isTakeoffReceived) {
            MissionManager.addTakeoffWP(altitude)
            isTakeoffReceived = true
        } else {
            ControlHandler.getInstance().simpleTakeoff()
        }
    }

    fun processLanding() {
        if (isReceivingMission) {
            // TODO: Complete landing + disarm sequence
            MissionManager.addLandingWP()
        } else {
            // TODO: reduce altitude first?
            ControlHandler.getInstance().landing()
        }
    }

    fun processWaypoint(
        seq: Int,
        latitude: Double,
        longitude: Double,
        altitude: Float,
        delay: Int,
        yaw: Float
    ) {
        if (isReceivingMission) {
            // if the first element and no TakeOff was received, add the TakeOff
            // and then displace towards the location
            if (seq == 0) {
                processTakeoff(altitude)
            }
            // Append the waypoint and actions
            MissionManager.addWaypoint(latitude, longitude, altitude)
            // Delay
            if (delay > 0)
                MissionManager.addActionDelay(delay)  // milliseconds
            // Yaw
            if (yaw > 0)
                MissionManager.addActionRotate((yaw * 180.0 / 3.141592).toInt()) // +-180 deg

            // TODO: should keep track of initial altitude? maybe is better to use the home location
            // TODO: take care of different frameReference
            // TODO: Limit to a max altitude and minimum altitude
            // TODO check altitude conversion

            Log.d(
                TAG,
                "Waypoint: ($latitude, $longitude)  at $altitude : Yaw $yaw deg - Delay $delay"
            )
        } else {
            ControlHandler.getInstance().flyTo()
        }
    }


    fun missionItem2djiWaypoint(msg: msg_mission_item_int): Boolean {
        val frameReference = msg.frame.toInt()
        // We only support the following frame models:
        // 0 = Global (WGS84) coordinate frame + altitude relative to mean sea level (MSL).
        // 3 = Global (WGS84) coordinate frame + altitude relative to the home position.
        if (frameReference != 0 && frameReference != 3) {
            Log.w(TAG, "$frameReference not available");
            sendAckAnswer(MAV_MISSION_RESULT.MAV_MISSION_UNSUPPORTED_FRAME)
            return false
        }

        var commandAccepted = true

        val latitude = coordinateMAVLink2DJI(msg.x)
        val longitude = coordinateMAVLink2DJI(msg.y)
        val altitude = msg.z

        when (msg.command) {
            MAV_CMD.MAV_CMD_NAV_TAKEOFF -> processTakeoff(altitude)

            MAV_CMD.MAV_CMD_NAV_LAND -> processLanding()

            MAV_CMD.MAV_CMD_NAV_WAYPOINT -> processWaypoint(
                msg.seq,
                latitude,
                longitude,
                altitude,
                msg.param1.toInt(),
                msg.param4
            )

            MAV_CMD.MAV_CMD_NAV_RETURN_TO_LAUNCH -> {
                Log.d(TAG, "MAV_CMD_NAV_RETURN_TO_LAUNCH");
//                mBuilder.finishedAction(WaypointMissionFinishedAction.GO_HOME)
            }

            MAV_CMD.MAV_CMD_NAV_DELAY -> {
                Log.d(TAG, "MAV_CMD_NAV_DELAY")
                val seconds = msg.param1
                MissionManager.addWaypoint(latitude, longitude, altitude)
                MissionManager.addActionDelay(seconds.toInt())
            }

            // TODO: split camera related messages to CameraController as corresponding service

            MAV_CMD.MAV_CMD_IMAGE_START_CAPTURE -> {
                Log.d(TAG, "MAV_CMD_IMAGE_START_CAPTURE")
                MissionManager.addWaypoint(latitude, longitude, altitude)
                MissionManager.addActionTakePhoto()
            }

            MAV_CMD.MAV_CMD_VIDEO_START_CAPTURE -> {
                Log.d(TAG, "MAV_CMD_VIDEO_START_CAPTURE")
                MissionManager.addWaypoint(latitude, longitude, altitude)
                MissionManager.addActionStartRecord()
            }

            MAV_CMD.MAV_CMD_VIDEO_STOP_CAPTURE -> {
                Log.d(TAG, "MAV_CMD_VIDEO_STOP_CAPTURE")
                MissionManager.addWaypoint(latitude, longitude, altitude)
                MissionManager.addActionStopRecord()
            }

            MAV_CMD.MAV_CMD_CONDITION_YAW -> {
                Log.d(TAG, "MAV_CMD_CONDITION_YAW")
                val angle = msg.param1 - 180  // [0, 360] -> [-180, 180]
                MissionManager.addWaypoint(latitude, longitude, altitude)
                MissionManager.addActionRotate(angle.toInt())
            }

            MAV_CMD.MAV_CMD_DO_CHANGE_SPEED -> {
                Log.d(TAG, "MAV_CMD_DO_CHANGE_SPEED")
                val speed = msg.param2
                if (speed < -15 || speed > 15) {
                    Log.w(TAG, "New speed limits [-15, 15], clipping value.")
                }
                flightSpeed = kotlin.math.min(kotlin.math.max(speed, -15f), 15f)
            }

            MAV_CMD.MAV_CMD_DO_MOUNT_CONTROL -> {
                Log.d(TAG, "MAV_CMD_DO_MOUNT_CONTROL")
                val pitch = msg.param1
                MissionManager.addWaypoint(latitude, longitude, altitude)
                MissionManager.addActionGimbalPitch(pitch.toInt())
            }

            MAV_CMD.MAV_CMD_DO_DIGICAM_CONTROL -> {
                Log.d(TAG, "MAV_CMD_DO_DIGICAM_CONTROL")
            }

            MAV_CMD.MAV_CMD_SET_CAMERA_ZOOM -> {
                Log.d(TAG, "MAV_CMD_SET_CAMERA_ZOOM")
            }

            MAV_CMD.MAV_CMD_SET_CAMERA_FOCUS -> {
                Log.d(TAG, "MAV_CMD_SET_CAMERA_FOCUS")
            }

            MAV_CMD.MAV_CMD_DO_SET_MISSION_CURRENT -> setCurrentMissionItem(msg)
        }
        return commandAccepted
    }

    fun sendReceivedItem(sequence: Int) {
        val msg = msg_mission_item_reached()
        msg.seq = sequence
        client.sendMessage(msg)
    }

    fun buildWaypointMission(startIndex: Int, onResult: (Boolean, String?) -> Unit) {
        var currentIndex = startIndex
        while (currentIndex <= numberOfStoredItems) {
            missionItem2djiWaypoint(missionItems[currentIndex])
            currentIndex += 1
        }
        MissionManager.buildMission(flightSpeed, onResult = onResult)
    }

    fun processMissionItem(msg: msg_mission_item_int) {
        if (!client.isTargetSystem(msg.target_system)) {
            return
        }
        // Validate sequence
        val expectedSeq = missionItems.size
        if (msg.seq != expectedSeq && isReceivingMission) {
            Log.e(TAG, "Received item #${msg.seq} instead #${expectedSeq}, re-requesting...")
            if (currentRetryTimes < maxRetryTimes) {
                requestMissionItem(expectedSeq)
                currentRetryTimes += 1
            } else Log.w(TAG, "Max re-requesting attempts reached")

            return
        }
        // Store item and request next or upload the mission
        if (isReceivingMission) {
            appendMissionItem(msg)
            sendReceivedItem(expectedSeq)

            if (msg.seq < numberOfExpectedItems) {
                // request next mission item
                requestMissionItem(expectedSeq + 1)
            } else {
                // reached the end of the mission items
                buildWaypointMission(0) { success, error ->
                    sendAckAnswer(
                        if (success) MAV_MISSION_RESULT.MAV_MISSION_ACCEPTED
                        else MAV_MISSION_RESULT.MAV_MISSION_DENIED
                    )
                    if (error != null) {
                        Log.e(TAG, error)
                    }
                }
                isReceivingMission = false
            }
        }
    }

    fun sendCurrentMission(sequence: Int) {
        val msg = msg_mission_current()
        msg.seq = sequence
        msg.mission_id = getMissionId()
        msg.mission_state = MISSION_STATE.MISSION_STATE_ACTIVE.toShort()
        client.sendMessage(msg)
    }

    fun sendStatusText(error: String, severity: Int) {
        val msg = msg_statustext()
        msg.text = error.toByteArray()
        msg.severity = severity.toShort()
        client.sendMessage(msg)
    }

    fun setCurrentMissionItem(msg: msg_mission_item_int) {
        MissionManager.runMission(false) { error ->
            if (error == null) {
                val reset = msg.param2 == 1f
                var sequence = msg.param1.toInt()
                // must reset mission
                if (sequence == -1 || reset)
                    sequence = 0

                buildWaypointMission(sequence) { success, error ->
                    if (success) {
                        sendCurrentMission(sequence)
                        MissionManager.runMission(true) { error ->
                            if (error != null) Log.e(TAG, "Unable to start mission: $error")
                        }
                    } else if (error != null) {
                        sendStatusText(error, MAV_SEVERITY.MAV_SEVERITY_ERROR)
                    }
                }
            } else {
                Log.e(TAG, "Unable to setCurrentMissionItem: $error")
            }
        }
    }

    // TODO: start, pause, and resume procedures
}
