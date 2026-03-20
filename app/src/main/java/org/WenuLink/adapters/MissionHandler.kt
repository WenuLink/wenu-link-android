package org.WenuLink.adapters

import com.MAVLink.common.msg_mission_item_int
import com.MAVLink.enums.MAV_CMD
import com.MAVLink.enums.MISSION_STATE
import io.getstream.log.taggedLogger
import org.WenuLink.adapters.mission.MissionAction
import org.WenuLink.adapters.mission.MissionAssembler
import org.WenuLink.sdk.MissionActionManager
import org.WenuLink.sdk.MissionManager

class MissionHandler {
    companion object {
        private var mInstance: MissionHandler? = null

        @Synchronized
        fun getInstance(): MissionHandler {
            if (mInstance == null) {
                mInstance = MissionHandler()
            }
            return mInstance!!
        }
    }

    private val logger by taggedLogger(MissionHandler::class.java.simpleName)
    private val assembler = MissionAssembler()
    var flightSpeed: Float = 5.0f
        private set
    var currentSequence = 0
        private set
    var isMissionRunning = false
        private set
    var currentState = MISSION_STATE.MISSION_STATE_UNKNOWN
        private set
    val totalNodes: Int
        get() = assembler.size()
    val currentId: Long
        get() = 202512

    @Synchronized
    fun updateState() {
        logger.d { "updateMissionState: ${MissionManager.currentState}" }
        currentState = when {
            MissionManager.isWaitingMission() -> MISSION_STATE.MISSION_STATE_NO_MISSION
            MissionManager.isMissionReady() -> MISSION_STATE.MISSION_STATE_NOT_STARTED
            MissionManager.isMissionStarted() -> MISSION_STATE.MISSION_STATE_ACTIVE
            MissionManager.isMissionPaused() -> MISSION_STATE.MISSION_STATE_PAUSED
            else -> currentState
        }
    }

    fun setSpeed(speed: Float) {
        val range = -15f..15f
        flightSpeed = speed.coerceIn(range)
        if (speed !in range) logger.w { "Clipped speed $speed to [$range]" }
    }

    fun getItemCoordinates(itemMsg: msg_mission_item_int): Coordinates3D = Coordinates3D(
        MessageUtils.coordinateMAVLink2DJI(itemMsg.x),
        MessageUtils.coordinateMAVLink2DJI(itemMsg.y),
        itemMsg.z
    )

    fun getWaypointNode(index: Int) = assembler.getNode(index)

    fun hasWaypointNodes() = assembler.hasNodes()

    fun canCreateMission() = currentState == MISSION_STATE.MISSION_STATE_NO_MISSION

    fun reset() {
        stopWaypoint()
        assembler.reset()
    }

    fun addWaypointNode(itemMsg: msg_mission_item_int): Boolean {
        logger.d { "Append mission item." }

        when (itemMsg.command) {
            MAV_CMD.MAV_CMD_NAV_TAKEOFF ->
                assembler.addTakeoff(getItemCoordinates(itemMsg))

            MAV_CMD.MAV_CMD_NAV_WAYPOINT -> assembleWaypointNode(itemMsg)

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

            else -> return false
        }

        return true
    }

    private fun assembleWaypointNode(itemMsg: msg_mission_item_int) {
        // Assumes Global only
        val coordinates = getItemCoordinates(itemMsg)
        val delay = itemMsg.param1.toInt()
        val yaw: Float = itemMsg.param4

        // TODO: airframe check
//         val frameReference = itemMsg.frame.toInt()
//         // We only support the following frame models:
//         // 0 = Global (WGS84) coordinate frame + altitude relative to mean sea level (MSL).
//         // 3 = Global (WGS84) coordinate frame + altitude relative to the home position.
//         if (frameReference != 0 && frameReference != 3) {
//             logger.w { "frameReference: $frameReference is not available" }
//             sendAckAnswer(MAV_MISSION_RESULT.MAV_MISSION_UNSUPPORTED_FRAME)
//             return
//         }

        assembler.addWaypoint(coordinates)

        // Delay (seconds)
        if (delay > 0) {
            assembler.addActionToLast(
                MissionAction.Delay(delay)
            )
        }

        // Yaw (rad → deg)
        if (yaw != 0f) {
            val deg = (yaw * 180f / Math.PI).toInt()
            assembler.addActionToLast(
                MissionAction.Rotate(deg)
            )
        }

        logger.d { "Waypoint: ($coordinates) (Yaw=$yaw deg) (Delay=$delay)" }
    }

    fun uploadWaypoints(onResult: (String?) -> Unit) {
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

    fun pauseWaypoint() {
        if (!isMissionRunning) return
        logger.i { "Pause WP mission" }
        MissionManager.pauseMission { error ->
            if (error != null) {
                logger.i { "Unable to pause the mission at $currentSequence: $error" }
            }
            updateState()
        }
    }

    fun startWaypoint() {
        if (isMissionRunning) return
        currentSequence = 0
        logger.i { "Start WP mission" }
        MissionManager.startMission { error ->
            if (error != null) logger.i { "Unable to start the mission: $error" }
            updateState()
        }
    }

    fun resumeWaypoint() {
        if (!isMissionRunning) return
        logger.i { "Resume WP mission" }
        MissionManager.resumeMission { error ->
            if (error != null) logger.i { "Unable to resume the mission: $error" }
            updateState()
        }
    }

    fun stopWaypoint() {
        if (!isMissionRunning) return
        currentSequence = 0
        logger.i { "Stop WP mission" }
        MissionManager.stopMission { error ->
            if (error != null) logger.i { "Unable to stop the mission: $error" }
            updateState()
        }
    }

    fun startListenersWaypoint(
        onStart: () -> Unit,
        onWaypointReach: (Int) -> Unit,
        onFinish: (String?) -> Unit
    ) {
        updateState()
        MissionManager.addListeners(
            onStart = {
                onStart()
                isMissionRunning = true
                updateState()
            },
            onWaypointReach = { index ->
                currentSequence = index
                updateState()
                onWaypointReach(currentSequence)
            },
            onFinish = { error ->
                onFinish(error)
                isMissionRunning = false
                updateState()
            }
        )
    }

    fun cancelCommand() {
        logger.i { "Cancel command" }
        MissionActionManager.stop()
        MissionActionManager.clear() // <- not sure about this
        updateState()
    }

    fun pauseCommand() {
        logger.i { "Pause command" }
        MissionActionManager.pause()
        updateState()
    }

    fun resumeCommand() {
        logger.i { "Resume command" }
        MissionActionManager.stop()
        updateState()
    }
    fun doReposition(target: Coordinates3D, speed: Float?, onResult: (String?) -> Unit) {
        logger.i { "doReposition" }
        MissionActionManager.clear()
        val error = MissionActionManager.scheduleGoTo(
            coordinates = target,
            speed = speed
        )

        if (error != null) {
            onResult("Error in reposition: ${error.description}")
            return
        }

        MissionActionManager.registerGoToFinished {
            onResult(null)
        }

        MissionActionManager.start()
    }

    fun doLand(onResult: (String?) -> Unit) {
        MissionActionManager.clear()
        val error = MissionActionManager.scheduleLand()

        if (error != null) {
            onResult("Error in Land: ${error.description}")
            return
        }

        logger.i { "scheduleLand" }

        MissionActionManager.registerLandFinished {
            onResult(null)
        }

        MissionActionManager.start()
    }
}
