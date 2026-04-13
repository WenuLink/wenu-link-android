package org.WenuLink.adapters.mission

import com.MAVLink.common.msg_mission_item_int
import com.MAVLink.enums.MAV_CMD
import com.MAVLink.enums.MISSION_STATE
import io.getstream.log.taggedLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.WenuLink.adapters.MessageUtils
import org.WenuLink.commands.CommandHandler
import org.WenuLink.commands.UnitResult
import org.WenuLink.sdk.MissionActionManager
import org.WenuLink.sdk.MissionManager

data class MissionState(
    val mavlink: Int = MISSION_STATE.MISSION_STATE_UNKNOWN,
    val id: Int = 202512,
    val startSequence: Int = 1,
    val currentSequence: Int? = null,
    val assembler: MissionAssembler = MissionAssembler(),
    val unvisitedSequence: Boolean = false
) {
    fun totalNodes() = assembler.size()

    fun isActive() = mavlink == MISSION_STATE.MISSION_STATE_ACTIVE

    fun canCreateMission() = mavlink == MISSION_STATE.MISSION_STATE_NO_MISSION

    fun canStartMission() = mavlink == MISSION_STATE.MISSION_STATE_NOT_STARTED

    fun canPauseMission() = mavlink == MISSION_STATE.MISSION_STATE_ACTIVE

    fun canResumeMission() = mavlink == MISSION_STATE.MISSION_STATE_PAUSED

    fun isComplete() = mavlink == MISSION_STATE.MISSION_STATE_COMPLETE

    fun setStartSequence(sequence: Int) = copy(startSequence = sequence)

    fun updateItemSequence(sequence: Int?) =
        copy(currentSequence = sequence, unvisitedSequence = true)

    fun nextItemSequence() = updateItemSequence(currentSequence?.plus(1))

    fun setComplete() = copy(mavlink = MISSION_STATE.MISSION_STATE_COMPLETE)

    fun markVisited() = copy(unvisitedSequence = false)

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
        return copy(startSequence = 1, currentSequence = null)
            .fromMissionManager()
            .markVisited()
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
    var flightSpeed = 5f
        private set
    var state = MissionState()
        private set

    override fun registerScope(scope: CoroutineScope) {
        MissionManager.addListeners { index ->
            scope.launch { setCurrentSequence(index) }
        }
        startCommandProcessor(scope, this@MissionHandler, logger)
    }

    override fun unload() {
        MissionManager.removeListener()
        super.unload()
    }

    @Synchronized
    fun syncState() {
        state = state.fromMissionManager()
    }

    @Synchronized
    fun processNode() {
        if (!state.unvisitedSequence) return

        logger.d { "Processing WP ${state.currentSequence}" }
        state = state.markVisited()
    }

    @Synchronized
    fun setStartSequence(sequence: Int): MissionState {
        state = state.setStartSequence(sequence)
        return state
    }

    @Synchronized
    fun setCurrentSequence(sequence: Int): MissionState {
        state = state.updateItemSequence(sequence)
        if (sequence == -1) state = state.setComplete().markVisited()
        return state
    }

    fun setSpeed(speed: Float) {
        val range = -15f..15f
        flightSpeed = speed.coerceIn(range)
        if (speed !in range) logger.w { "Clipped speed $speed to [$range]" }
    }

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
                state.assembler.addTakeoff(
                    MessageUtils.xyzMAVLink2Coordinates(itemMsg.x, itemMsg.y, itemMsg.z)
                )

            MAV_CMD.MAV_CMD_NAV_WAYPOINT -> assembleWaypointNode(itemMsg)

            MAV_CMD.MAV_CMD_NAV_DELAY ->
                state.assembler.addActionToLast(DelayAction.fromMissionItem(itemMsg))

            MAV_CMD.MAV_CMD_CONDITION_YAW ->
                state.assembler.addActionToLast(RotateAction(itemMsg.param1))

            MAV_CMD.MAV_CMD_IMAGE_START_CAPTURE ->
                state.assembler.addActionToLast(
                    PhotoAction(itemMsg.param3.toInt(), itemMsg.param2.toInt())
                )

            MAV_CMD.MAV_CMD_IMAGE_STOP_CAPTURE ->
                state.assembler.addActionToLast(StopPhotoAction)

            MAV_CMD.MAV_CMD_VIDEO_START_CAPTURE ->
                state.assembler.addActionToLast(VideoAction())

            MAV_CMD.MAV_CMD_VIDEO_STOP_CAPTURE ->
                state.assembler.addActionToLast(StopVideoAction)

            MAV_CMD.MAV_CMD_NAV_RETURN_TO_LAUNCH ->
                state.assembler.setRTLWhenFinish()

            else -> return false
        }

        return true
    }

    private fun assembleWaypointNode(itemMsg: msg_mission_item_int) {
        // Assumes Global only
        val coordinates = MessageUtils.xyzMAVLink2Coordinates(itemMsg.x, itemMsg.y, itemMsg.z)
        val delay = itemMsg.param1.toInt()
        val deg = itemMsg.param4

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
            state.assembler.addActionToLast(DelayAction(delay * 1000L))
        }

        // Yaw
        if (deg != 0f) {
            state.assembler.addActionToLast(RotateAction(deg))
        }

        logger.d { "Waypoint: ($coordinates) (Yaw=$deg deg) (Delay=$delay)" }
    }

    fun uploadWaypoints(onResult: (UnitResult) -> Unit) =
        dispatchCommand(UploadMissionCommand(state.assembler, flightSpeed), onResult)
}
