package org.WenuLink.adapters

import com.MAVLink.common.msg_mission_item_int
import com.MAVLink.enums.MAV_CMD
import com.MAVLink.enums.MISSION_STATE
import io.getstream.log.taggedLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.WenuLink.adapters.aircraft.Coordinates3D
import org.WenuLink.adapters.commands.CommandHandler
import org.WenuLink.adapters.mission.MissionAction
import org.WenuLink.adapters.mission.MissionAssembler
import org.WenuLink.adapters.mission.PauseActionCommand
import org.WenuLink.adapters.mission.PauseWaypointMission
import org.WenuLink.adapters.mission.ResumeActionCommand
import org.WenuLink.adapters.mission.ResumeWaypointMission
import org.WenuLink.adapters.mission.StartWaypointMission
import org.WenuLink.adapters.mission.StopWaypointMission
import org.WenuLink.adapters.mission.UploadMissionCommand
import org.WenuLink.sdk.MissionActionManager
import org.WenuLink.sdk.MissionManager

class MissionHandler : CommandHandler<MissionHandler>() {
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

    override fun registerScope(scope: CoroutineScope) {
        MissionManager.addListeners(
            onStart = {
                scope.launch {
                    isMissionRunning = true
                    updateState()
                }
            },
            onWaypointReach = { index ->
                scope.launch {
                    currentSequence = index
                    updateState()
                }
            },
            onFinish = { error ->
                scope.launch {
                    isMissionRunning = false
                    updateState()
                }
            }
        )
        startCommandProcessor(scope, this@MissionHandler, logger)
    }

//    override fun unload() {
//        super.unload()
//    }

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

    fun clear() {
        stopWaypoint()
        assembler.reset()
        stopCommand()
        MissionActionManager.clear()
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

    fun uploadWaypoints(onResult: (String?) -> Unit) =
        dispatchCommand(UploadMissionCommand(assembler, flightSpeed), onResult)

    fun setItemSequenceIndex(sequence: Int) {
        currentSequence = sequence
    }

    fun pauseWaypoint() {
        logger.i { "Pause WP mission" }
        dispatchCommand(PauseWaypointMission) { error ->
            if (error !=
                null
            ) {
                logger.i { "Unable to pause the mission at $currentSequence: $error" }
            }
        }
    }

    fun resumeWaypoint() {
        logger.i { "Resume WP mission" }
        dispatchCommand(ResumeWaypointMission) { error ->
            if (error != null) logger.i { "Unable to resume the mission: $error" }
        }
    }

    fun stopWaypoint() {
        logger.i { "Stop WP mission" }
        dispatchCommand(StopWaypointMission) { error ->
            if (error != null) logger.i { "Unable to stop the mission: $error" }
        }
    }

    fun pauseCommand() {
        logger.i { "Pause WP mission" }
        dispatchCommand(PauseActionCommand) { error ->
            if (error !=
                null
            ) {
                logger.i { "Unable to pause the command: $error" }
            }
        }
    }

    fun resumeCommand() {
        logger.i { "Resume WP mission" }
        dispatchCommand(ResumeActionCommand) { error ->
            if (error != null) logger.i { "Unable to resume the command: $error" }
        }
    }
}
