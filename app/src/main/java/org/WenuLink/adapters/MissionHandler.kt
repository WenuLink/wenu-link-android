package org.WenuLink.adapters

import com.MAVLink.common.msg_mission_item_int
import com.MAVLink.enums.MAV_CMD
import com.MAVLink.enums.MISSION_STATE
import io.getstream.log.taggedLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.WenuLink.adapters.mission.MissionAction
import org.WenuLink.adapters.mission.MissionAssembler
import org.WenuLink.sdk.MissionManager
import kotlin.getValue
import kotlin.math.max
import kotlin.math.min

class MissionHandler {
    companion object {
        private var mInstance: MissionHandler? = null

        @Synchronized
        fun getInstance(): MissionHandler {
            if (mInstance == null)
                mInstance = MissionHandler()
            return mInstance!!
        }

    }

    private val logger by taggedLogger("MissionHandler")
    private val assembler = MissionAssembler()
    private var flightSpeed: Float = 5.0F
    var currentSequence = 0
        private set
    var isMissionRunning = false
        private set
    var currentState = MISSION_STATE.MISSION_STATE_UNKNOWN
        private set
    val totalNodes: Int
        get() { return assembler.size() }
    val currentId: Long
        get() { return 202512 }

    suspend fun onStart() {
        logger.d { "Mission started" }
        isMissionRunning = true
        updateState()
        // Must validate to which state must transit, assumes that starts from the ground
        // Possibly change to waitAltitude
        AircraftHandler.getInstance().waitLandedStateTransition(true)
        logger.d { "MissionManager state: ${MissionManager.getWaypointMissionState()}" }
    }

    suspend fun onFinish(error: String?) {
        logger.d { "Mission finish" }
        if (error != null) {
            logger.i { "Mission finished with error: $error" }
            return
        }
        // TODO: wait for launch location reaching when RTL, onFinish is called earlier
        // This also assumes landing when finish always
        AircraftHandler.getInstance().waitLandingConfirmation()

        AircraftHandler.getInstance().waitLandedStateTransition(false)
        logger.d { "MissionManager state: ${MissionManager.getWaypointMissionState()}" }
        isMissionRunning = false
        updateState()
    }

    suspend fun onWaypointReach(nextIndex: Int) {
        logger.d { "Waypoint reached" }
        currentSequence = nextIndex
        updateState()

        if (nextIndex != 1) return
        // Call pause only for first element
        pause()

        // Wait for AUTO mode to resume
//        handlerScope.launch {
        fun isAutoMode() = AircraftHandler.getInstance().copterFlightMode == ArduCopterFlightMode.AUTO
        AsyncUtils.waitReady(100L, ::isAutoMode)
        resume()
//        }
    }

    fun registerHandlerScope(handlerScope: CoroutineScope) {
        MissionManager.addListeners(
            onStart = {
                handlerScope.launch { onStart() }
            },
            onWaypointReach = { index ->
                handlerScope.launch { onWaypointReach(index) }
            },
            onFinish = { error ->
                handlerScope.launch { onFinish(error) }
            }
        )
        updateState()
    }

    @Synchronized
    fun updateState() {
        logger.d { "updateMissionState: ${MissionManager.getWaypointMissionState()}" }
        if (MissionManager.isWaitingMission())
            currentState = MISSION_STATE.MISSION_STATE_NO_MISSION
        else if (MissionManager.isMissionReady())
            currentState = MISSION_STATE.MISSION_STATE_NOT_STARTED
        else if (MissionManager.isMissionStarted())
            currentState = MISSION_STATE.MISSION_STATE_ACTIVE
        else if (MissionManager.isMissionPaused())
            currentState = MISSION_STATE.MISSION_STATE_PAUSED
    }

    fun setSpeed(speed: Float) {
        if (speed < -15F || speed > 15F) logger.w { "Clipping new speed $speed in [-15, 15]" }
        flightSpeed = min(max(speed, -15F), 15F)
    }

    fun getItemCoordinates(itemMsg: msg_mission_item_int): Coordinates3D {
        val latitude = MessageUtils.coordinateMAVLink2DJI(itemMsg.x)
        val longitude = MessageUtils.coordinateMAVLink2DJI(itemMsg.y)
        return Coordinates3D(latitude, longitude, itemMsg.z)
    }

    fun getWaypointNode(index: Int) = assembler.getNode(index)

    fun hasWaypointNodes() = assembler.hasNodes()

    fun reset() {
        assembler.reset()
    }

    fun addWaypointNode(itemMsg: msg_mission_item_int): Boolean {
        var commandAccepted = true
        logger.d { "Append mission item."}

        when (itemMsg.command) {
            MAV_CMD.MAV_CMD_NAV_TAKEOFF ->
                assembler.addTakeoff(getItemCoordinates(itemMsg))

            MAV_CMD.MAV_CMD_NAV_WAYPOINT -> {
                // Assumes Global only
                val coordinates = getItemCoordinates(itemMsg)
                val delay = itemMsg.param1.toInt()
                val yaw: Float = itemMsg.param4

                // TODO: airframe check
//                val frameReference = itemMsg.frame.toInt()
//                // We only support the following frame models:
//                // 0 = Global (WGS84) coordinate frame + altitude relative to mean sea level (MSL).
//                // 3 = Global (WGS84) coordinate frame + altitude relative to the home position.
//                if (frameReference != 0 && frameReference != 3) {
//                    logger.w { "frameReference: $frameReference is not available" }
//                    sendAckAnswer(MAV_MISSION_RESULT.MAV_MISSION_UNSUPPORTED_FRAME)
//                    return
//                }

                assembler.addWaypoint(coordinates)

                // Delay (seconds)
                if (delay > 0)
                    assembler.addActionToLast(
                        MissionAction.Delay(delay)
                    )

                // Yaw (rad → deg)
                if (yaw != 0f) {
                    val deg = (yaw * 180f / Math.PI).toInt()
                    assembler.addActionToLast(
                        MissionAction.Rotate(deg)
                    )
                }

                logger.d { "Waypoint: ($coordinates) (Yaw=$yaw deg) (Delay=$delay)" }
            }

            MAV_CMD.MAV_CMD_NAV_DELAY ->
                assembler.addActionToLast(MissionAction.Delay(itemMsg.param1.toInt()))

            MAV_CMD.MAV_CMD_CONDITION_YAW ->
                assembler.addActionToLast(MissionAction.Rotate(itemMsg.param1.toInt()))

            MAV_CMD.MAV_CMD_IMAGE_START_CAPTURE ->
                assembler.addActionToLast(MissionAction.TakePhoto)

            MAV_CMD.MAV_CMD_VIDEO_START_CAPTURE ->
                assembler.addActionToLast(MissionAction.StartRecord)

            MAV_CMD.MAV_CMD_VIDEO_STOP_CAPTURE ->
                assembler.addActionToLast(MissionAction.StopRecord)

            MAV_CMD.MAV_CMD_NAV_RETURN_TO_LAUNCH ->
                assembler.setRTLWhenFinish()

            else -> commandAccepted = false
        }

        return commandAccepted
    }

    fun upload(onResult: (String?) -> Unit) {
        if (!MissionManager.isWaitingMission()) return

        val mission = assembler.build()
        MissionManager.uploadMission(
            mission,
            autoSpeed = flightSpeed
        ) { success, error ->
            logger.i { "Mission accepted: $success $error" }
            updateState()
            onResult(error)
        }
    }

    fun pause() {
        if (!isMissionRunning) return
        MissionManager.pauseMission { error ->
            if (error == null) {
                logger.d { "Paused on sequence $currentSequence" }
                updateState()
            }
        }
    }

    fun start() {
        if (isMissionRunning) return
        currentSequence = 0
        MissionManager.startMission { error ->
            if (error != null) logger.i { "Unable to start the mission" }
        }
    }

    fun resume() {
        if (!isMissionRunning) return
        MissionManager.resumeMission { error ->
            if (error != null) logger.i { "Unable to resume the mission" }
        }
    }
}