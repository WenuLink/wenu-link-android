package org.WenuLink.sdk

import dji.common.mission.waypoint.Waypoint
import dji.common.mission.waypoint.WaypointAction
import dji.common.mission.waypoint.WaypointActionType
import dji.common.mission.waypoint.WaypointMission
import dji.common.mission.waypoint.WaypointMissionFinishedAction
import dji.common.mission.waypoint.WaypointMissionFlightPathMode
import dji.common.mission.waypoint.WaypointMissionGotoWaypointMode
import dji.common.mission.waypoint.WaypointMissionHeadingMode
import dji.common.mission.waypoint.WaypointMissionState
import dji.sdk.mission.MissionControl
import dji.sdk.mission.waypoint.WaypointMissionOperator
import io.getstream.log.taggedLogger
import kotlin.getValue


object MissionManager {
    private val logger by taggedLogger("MissionManager")
    private val waypointItems = ArrayList<Waypoint?>()

    fun getWaypointMissionOperator(): WaypointMissionOperator {
//        return DJISDKManager.getInstance().missionControl.waypointMissionOperator
        return MissionControl.getInstance().waypointMissionOperator
    }

    fun getWaypointMission(): WaypointMission? {
        return getWaypointMissionOperator().loadedMission
    }

    private val waypointTypes = mapOf(
        // movement
//        Pair(10, WaypointActionType.MAV_CMD_NAV_TAKEOFF),
//        Pair(11, WaypointActionType.MAV_CMD_NAV_WAYPOINT),
        Pair(12, WaypointActionType.STAY),
        // orientation
        Pair(20, WaypointActionType.ROTATE_AIRCRAFT),
        Pair(21, WaypointActionType.GIMBAL_PITCH),
        Pair(21, WaypointActionType.FINE_TUNE_GIMBAL_PITCH),
//          TODO: https://mavlink.io/en/services/gimbal_v2.html
        // camera triggers
        Pair(30, WaypointActionType.CAMERA_ZOOM),
        Pair(31, WaypointActionType.CAMERA_FOCUS),
        Pair(32, WaypointActionType.START_TAKE_PHOTO),
        Pair(33, WaypointActionType.START_RECORD),
        Pair(33, WaypointActionType.PHOTO_GROUPING),
        Pair(34, WaypointActionType.STOP_RECORD),
    )

    fun waypointActionIndex(action: WaypointAction): Int {
        //  Solution used direct access instead of ordinal for a better control of what is already implemented
        return waypointTypes.entries
            .firstOrNull { it.value == action.actionType }
            ?.key ?: 11 // waypoint as default
    }

    fun flushWaypoints() {
        getWaypointMission()?.waypointList?.clear()
        waypointItems.clear()
    }

    fun generateWaypointMissionBuilder(
        autoSpeed: Float = 2f,
        maxSpeed: Float = 6f,
        curvedPath: Boolean = true
    ): WaypointMission.Builder? {
        val wpBuilder = WaypointMission.Builder()
            .autoFlightSpeed(autoSpeed)
            .maxFlightSpeed(maxSpeed)
            .setExitMissionOnRCSignalLostEnabled(false)
            .finishedAction(WaypointMissionFinishedAction.NO_ACTION)
            .gotoFirstWaypointMode(WaypointMissionGotoWaypointMode.SAFELY)
            .headingMode(WaypointMissionHeadingMode.AUTO)
            .repeatTimes(1)

        if (curvedPath) {
            wpBuilder.flightPathMode(WaypointMissionFlightPathMode.CURVED)
        } else {
            wpBuilder.flightPathMode(WaypointMissionFlightPathMode.NORMAL)
        }
        return wpBuilder
    }

    fun addWaypoint(x: Double, y: Double, z: Float) {
        val waypoint = Waypoint(x, y, z)
        logger.d { "Add waypoint: $waypoint" }
        waypointItems.add(waypoint)
    }

    fun getLastWaypoint(): Waypoint? = waypointItems[waypointItems.size - 1]

    fun addTakeoffWP(z: Float) {
        val (currentLat, currentLong) = FCManager.getCurrentLocation()
        addWaypoint(currentLat!!, currentLong!!, z)
        logger.d { "Add waypoint takeoff: ${getLastWaypoint()}" }
    }

    fun addLandingWP(z: Float = 1.2f) {
        val (currentLat, currentLong) = FCManager.getCurrentLocation()
        addWaypoint(currentLat!!, currentLong!!, z)
        logger.w { "Add fake waypoint landing: ${getLastWaypoint()}" }
    }

    fun addActionDelay(seconds: Int, waypoint: Waypoint? = getLastWaypoint()) {
        val delayTime = kotlin.math.min(kotlin.math.max(seconds * 1000, 0), 32767)
        waypoint?.addAction(WaypointAction(WaypointActionType.STAY, delayTime)) // milliseconds
    }

    /**
     * Add a WaypointActionType
     * @param degrees: the degree value between [-180, 180]
     */
    fun addActionRotate(degrees: Int, waypoint: Waypoint? = getLastWaypoint()) {
        val clipDegrees = kotlin.math.min(kotlin.math.max(degrees, -180), 180)
        waypoint?.addAction(WaypointAction(WaypointActionType.ROTATE_AIRCRAFT, clipDegrees))
    }

    fun addActionGimbalPitch(pitch: Int, waypoint: Waypoint? = getLastWaypoint()) {
        waypoint?.addAction(WaypointAction(WaypointActionType.GIMBAL_PITCH, pitch))
    }

    fun addActionTakePhoto(waypoint: Waypoint? = getLastWaypoint()) {
        waypoint?.addAction(WaypointAction(WaypointActionType.START_TAKE_PHOTO, 0))
    }

    fun addActionStartRecord(waypoint: Waypoint? = getLastWaypoint()) {
        waypoint?.addAction(WaypointAction(WaypointActionType.START_RECORD, 0))
    }

    fun addActionStopRecord(waypoint: Waypoint? = getLastWaypoint()) {
        waypoint?.addAction(WaypointAction(WaypointActionType.STOP_RECORD, 0))
    }

    fun uploadWaypointMission(onResult: (Boolean, String?) -> Unit) {
        logger.i { "Uploading mission" }
        getWaypointMissionOperator().uploadMission(
            SDKUtils.createCompletionCallback { error ->
                if (error == null) {
                    while (getWaypointMissionOperator().currentState == WaypointMissionState.UPLOADING) {
                        try {
                            Thread.sleep(100)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                    if (getWaypointMissionOperator().currentState == WaypointMissionState.READY_TO_EXECUTE
                    ) onResult(true, null)
                    else onResult(false, "Error uploading waypoint mission!")
                } else onResult(
                    false,
                    "Error uploading mission! $error (${getWaypointMissionOperator().currentState})"
                )
            }
        )
    }

    fun buildMission(
        autoSpeed: Float = 2f,
        maxSpeed: Float = 6f,
        curvedPath: Boolean = true,
        onResult: (Boolean, String?) -> Unit
    ) {
        // Generate MissionBuilder and add Waypoints
        val missionBuilder = generateWaypointMissionBuilder(autoSpeed, maxSpeed, curvedPath)
//        missionBuilder?.waypointList(waypointItems)?.waypointCount(waypointItems.size)
        waypointItems.forEach { missionBuilder?.addWaypoint(it!!) }
        missionBuilder?.waypointCount(waypointItems.size)
        // Process WaypointMission
        val buildResult = getWaypointMissionOperator().loadMission(
            missionBuilder?.build() as WaypointMission
        )
        if (buildResult != null) {
            logger.w { "Error in loading mission: $buildResult" }
            onResult(false, buildResult.description)
        } else uploadWaypointMission(onResult)
    }

    fun runMission(run: Boolean, onResult: (String?) -> Unit) {
        if (run && getWaypointMissionOperator().currentState
            in listOf<WaypointMissionState>(
                WaypointMissionState.READY_TO_EXECUTE,
                WaypointMissionState.EXECUTION_PAUSED
            )
        ) getWaypointMissionOperator().startMission(
            SDKUtils.createCompletionCallback(onResult)
        )
        else getWaypointMissionOperator().stopMission(
            SDKUtils.createCompletionCallback(onResult)
        )
    }

    fun pauseMission(onResult: (String?) -> Unit) {
        if (getWaypointMissionOperator().currentState == WaypointMissionState.EXECUTING
        ) getWaypointMissionOperator().pauseMission(
            SDKUtils.createCompletionCallback(onResult)
        )
    }
}
