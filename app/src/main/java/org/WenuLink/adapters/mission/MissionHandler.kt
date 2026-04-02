package org.WenuLink.adapters.mission

import com.MAVLink.common.msg_mission_item_int
import com.MAVLink.enums.MAV_CMD
import com.MAVLink.enums.MISSION_STATE
import io.getstream.log.taggedLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.WenuLink.adapters.MessageUtils
import org.WenuLink.adapters.aircraft.Coordinates3D
import org.WenuLink.commands.CommandHandler
import org.WenuLink.sdk.MissionActionManager
import org.WenuLink.sdk.MissionManager

data class MissionState(
    val mavlink: Int = MISSION_STATE.MISSION_STATE_UNKNOWN,
    val sequence: Int? = null,
    val assembler: MissionAssembler = MissionAssembler(),
    val id: Int = 202512,
    val unvisitedSequence: Boolean = false
) {
    fun totalNodes() = assembler.size()

    fun isActive() = mavlink == MISSION_STATE.MISSION_STATE_ACTIVE

    fun canCreateMission() = mavlink == MISSION_STATE.MISSION_STATE_NO_MISSION

    fun canStartMission() = mavlink == MISSION_STATE.MISSION_STATE_NOT_STARTED

    fun canPauseMission() = mavlink == MISSION_STATE.MISSION_STATE_ACTIVE

    fun canResumeMission() = mavlink == MISSION_STATE.MISSION_STATE_PAUSED

    fun isComplete() = mavlink == MISSION_STATE.MISSION_STATE_COMPLETE

    fun updateItemSequence(sequence: Int?) = copy(sequence = sequence, unvisitedSequence = true)

    fun nextItemSequence() = updateItemSequence(sequence?.plus(1))

    fun setComplete() = copy(mavlink = MISSION_STATE.MISSION_STATE_COMPLETE)

    fun markSequenceOld() = copy(unvisitedSequence = false)

    fun fromMissionManager() = copy(
        mavlink = when {
            MissionManager.isWaitingMission() -> MISSION_STATE.MISSION_STATE_NO_MISSION
            MissionManager.isMissionReady() -> MISSION_STATE.MISSION_STATE_NOT_STARTED
            MissionManager.isMissionStarted() -> MISSION_STATE.MISSION_STATE_ACTIVE
            MissionManager.isMissionPaused() -> MISSION_STATE.MISSION_STATE_PAUSED
            else -> MISSION_STATE.MISSION_STATE_UNKNOWN
        }
    )

    fun reset(): MissionState {
        assembler.reset()
        return copy(sequence = null)
            .fromMissionManager()
            .markSequenceOld()
    }
}

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
    var flightSpeed = 5.0f
        private set
    var state = MissionState()
        private set

    override fun registerScope(scope: CoroutineScope) {
        MissionManager.addListeners { index ->
            scope.launch { setItemSequence(index) }
        }
        startCommandProcessor(scope, this@MissionHandler, logger)
    }

    override fun unload() {
        MissionManager.removeListener()
        super.unload()
    }

    @Synchronized
    fun syncState() {
        state = state
            .fromMissionManager()
            .markSequenceOld()
        logger.d { "updateMissionState: $state" }
    }

    @Synchronized
    fun setItemSequence(sequence: Int): MissionState {
        state = state.updateItemSequence(sequence)
        if (sequence == -1) state = state.setComplete()
        return state
    }

    @Synchronized
    fun nextItemSequence(): MissionState {
        state = state.nextItemSequence()
        return state
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

    fun getWaypointNode(index: Int) = state.assembler.getNode(index)

    fun hasWaypointNodes() = state.assembler.hasNodes()

    fun clear() {
        state = state.reset()
        MissionActionManager.clear()
    }

    fun addWaypointNode(itemMsg: msg_mission_item_int): Boolean {
        logger.d { "Append mission item." }

        when (itemMsg.command) {
            MAV_CMD.MAV_CMD_NAV_TAKEOFF ->
                state.assembler.addTakeoff(getItemCoordinates(itemMsg))

            MAV_CMD.MAV_CMD_NAV_WAYPOINT -> assembleWaypointNode(itemMsg)

            MAV_CMD.MAV_CMD_NAV_DELAY ->
                state.assembler.addActionToLast(MissionAction.Delay(itemMsg.param1.toInt()))

            MAV_CMD.MAV_CMD_CONDITION_YAW ->
                state.assembler.addActionToLast(MissionAction.Rotate(itemMsg.param1.toInt()))

            MAV_CMD.MAV_CMD_IMAGE_START_CAPTURE ->
                state.assembler.addActionToLast(MissionAction.TakePhoto)

            MAV_CMD.MAV_CMD_VIDEO_START_CAPTURE ->
                state.assembler.addActionToLast(MissionAction.StartRecord)

            MAV_CMD.MAV_CMD_VIDEO_STOP_CAPTURE ->
                state.assembler.addActionToLast(MissionAction.StopRecord)

            MAV_CMD.MAV_CMD_NAV_RETURN_TO_LAUNCH ->
                state.assembler.setRTLWhenFinish()

            else -> return false
        }

        return true
    }

    private fun assembleWaypointNode(itemMsg: msg_mission_item_int) {
        // Assumes Global only
        val coordinates = getItemCoordinates(itemMsg)
        val delay = itemMsg.param1.toInt()
        val yaw = itemMsg.param4

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

        state.assembler.addWaypoint(coordinates)

        // Delay (seconds)
        if (delay > 0) {
            state.assembler.addActionToLast(
                MissionAction.Delay(delay)
            )
        }

        // Yaw (rad → deg)
        if (yaw != 0f) {
            val deg = (yaw * 180f / Math.PI).toInt()
            state.assembler.addActionToLast(
                MissionAction.Rotate(deg)
            )
        }

        logger.d { "Waypoint: ($coordinates) (Yaw=$yaw deg) (Delay=$delay)" }
    }

    fun uploadWaypoints(onResult: (String?) -> Unit) =
        dispatchCommand(UploadMissionCommand(state.assembler, flightSpeed), onResult)
}
