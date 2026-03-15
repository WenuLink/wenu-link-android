package org.WenuLink.sdk

import dji.common.error.DJIError
import dji.common.mission.waypoint.Waypoint
import dji.common.mission.waypoint.WaypointAction
import dji.common.mission.waypoint.WaypointActionType
import dji.common.mission.waypoint.WaypointMission
import dji.common.mission.waypoint.WaypointMissionDownloadEvent
import dji.common.mission.waypoint.WaypointMissionExecuteState
import dji.common.mission.waypoint.WaypointMissionExecutionEvent
import dji.common.mission.waypoint.WaypointMissionFinishedAction
import dji.common.mission.waypoint.WaypointMissionFlightPathMode
import dji.common.mission.waypoint.WaypointMissionGotoWaypointMode
import dji.common.mission.waypoint.WaypointMissionHeadingMode
import dji.common.mission.waypoint.WaypointMissionState
import dji.common.mission.waypoint.WaypointMissionUploadEvent
import dji.sdk.mission.MissionControl
import dji.sdk.mission.waypoint.WaypointMissionOperator
import dji.sdk.mission.waypoint.WaypointMissionOperatorListener
import io.getstream.log.taggedLogger
import org.WenuLink.adapters.mission.AssembledMission
import org.WenuLink.adapters.mission.MissionAction
import org.WenuLink.adapters.mission.MissionNode

/**
 * Object class to comply with DJI ref
 * https://developer.dji.com/api-reference/android-api/Components/Missions/DJIWaypointMissionOperator.html
 */
object MissionManager {
    private val logger by taggedLogger(MissionManager::class.java.simpleName)
    private val operator: WaypointMissionOperator
        get() = MissionControl.getInstance().waypointMissionOperator
    val currentState: WaypointMissionState
        get() = operator.currentState
    val loadedMission: WaypointMission?
        get() = operator.loadedMission

    fun generateWaypointMissionBuilder(
        autoSpeed: Float = 2f,
        maxSpeed: Float = 6f,
        curvedPath: Boolean = true,
        rtlWhenFinish: Boolean = false
    ): WaypointMission.Builder? {
        val wpBuilder = WaypointMission.Builder()
            .autoFlightSpeed(autoSpeed)
            .maxFlightSpeed(maxSpeed)
            .setExitMissionOnRCSignalLostEnabled(false)
            .gotoFirstWaypointMode(WaypointMissionGotoWaypointMode.SAFELY)
            .headingMode(WaypointMissionHeadingMode.AUTO)
            .repeatTimes(1)

        if (curvedPath) {
            wpBuilder.flightPathMode(WaypointMissionFlightPathMode.CURVED)
        } else {
            wpBuilder.flightPathMode(WaypointMissionFlightPathMode.NORMAL)
        }

        if (rtlWhenFinish) {
            wpBuilder.finishedAction(WaypointMissionFinishedAction.GO_HOME)
        } else {
            wpBuilder.finishedAction(WaypointMissionFinishedAction.NO_ACTION)
        }

        return wpBuilder
    }

    fun isWaitingMission() = currentState == WaypointMissionState.READY_TO_UPLOAD

    fun isMissionReady() = currentState == WaypointMissionState.READY_TO_EXECUTE

    fun isMissionStarted() = currentState == WaypointMissionState.EXECUTING

    fun canStartMission() = currentState == WaypointMissionState.READY_TO_EXECUTE ||
        currentState == WaypointMissionState.EXECUTION_PAUSED

    fun isMissionPaused() = currentState == WaypointMissionState.EXECUTION_PAUSED

    fun uploadWaypointMission(onResult: (Boolean, String?) -> Unit) {
        logger.i { "Uploading mission" }
        operator.uploadMission(
            SDKUtils.createCompletionCallback { error ->
                if (error == null) {
                    while (currentState == WaypointMissionState.UPLOADING) {
                        try {
                            Thread.sleep(100)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                    if (isMissionReady()) {
                        onResult(true, null)
                    } else {
                        onResult(false, "Error uploading waypoint mission!")
                    }
                } else {
                    onResult(
                        false,
                        "Error uploading mission! $error ($currentState)"
                    )
                }
            }
        )
    }

    fun uploadMission(
        mission: AssembledMission,
        autoSpeed: Float,
        onResult: (Boolean, String?) -> Unit
    ) {
        val builder = generateWaypointMissionBuilder(
            autoSpeed = autoSpeed,
            rtlWhenFinish = mission.rtlWhenFinish
        ) ?: return onResult(false, "Builder error")

        mission.nodes.forEach { node ->
            when (node) {
                is MissionNode.Takeoff -> {
                    // No need to process taking off
                }

                is MissionNode.Land -> {
                    // TODO?
                }

                is MissionNode.Waypoint -> {
                    val coordinates = node.coordinates3D
                    val wp = Waypoint(coordinates.lat, coordinates.long, coordinates.alt)
                    node.actions.forEach { action ->
                        wp.addAction(mapAction(action))
                    }
                    builder.addWaypoint(wp)
                }
            }
        }

        builder.waypointCount(mission.nWaypoints)
        val error = operator.loadMission(builder.build())
        if (error != null) {
            onResult(false, error.description)
        } else {
            uploadWaypointMission(onResult)
        }
    }

    private fun mapAction(action: MissionAction): WaypointAction = when (action) {
        is MissionAction.Delay ->
            WaypointAction(WaypointActionType.STAY, action.seconds * 1000)

        is MissionAction.Rotate ->
            WaypointAction(WaypointActionType.ROTATE_AIRCRAFT, action.degrees)

        is MissionAction.GimbalPitch ->
            WaypointAction(WaypointActionType.GIMBAL_PITCH, action.pitch)

        MissionAction.TakePhoto ->
            WaypointAction(WaypointActionType.START_TAKE_PHOTO, 0)

        MissionAction.StartRecord ->
            WaypointAction(WaypointActionType.START_RECORD, 0)

        MissionAction.StopRecord ->
            WaypointAction(WaypointActionType.STOP_RECORD, 0)
    }

    fun startMission(onResult: (String?) -> Unit) {
        if (canStartMission()) {
            operator.startMission(
                SDKUtils.createCompletionCallback(onResult)
            )
        } else {
            onResult("No mission to start.")
        }
    }

    fun stopMission(onResult: (String?) -> Unit) {
        if (isMissionStarted()) {
            operator.stopMission(
                SDKUtils.createCompletionCallback(onResult)
            )
        } else {
            onResult("Not executing mission.")
        }
    }

    fun pauseMission(onResult: (String?) -> Unit) {
        if (isMissionStarted()) {
            operator.pauseMission(
                SDKUtils.createCompletionCallback(onResult)
            )
        } else {
            onResult("Not executing mission.")
        }
    }

    fun resumeMission(onResult: (String?) -> Unit) {
        if (isMissionPaused()) {
            operator.resumeMission(
                SDKUtils.createCompletionCallback(onResult)
            )
        } else {
            onResult("No mission to resume.")
        }
    }

    fun addListeners(
        onStart: () -> Unit,
        onWaypointReach: (Int) -> Unit,
        onFinish: (String?) -> Unit
    ) {
        logger.d { "addListeners" }
        operator.addListener(
            object : WaypointMissionOperatorListener {

                override fun onUploadUpdate(p0: WaypointMissionUploadEvent) {
                }

                override fun onDownloadUpdate(p0: WaypointMissionDownloadEvent) {
                }

                override fun onExecutionStart() {
                    logger.d { "onExecutionStart" }
                    onStart()
                }

                override fun onExecutionUpdate(p0: WaypointMissionExecutionEvent) {
                    val progress = p0.progress ?: return
                    logger.d { "onExecutionUpdate progress: $progress" }
                    if (progress.executeState == WaypointMissionExecuteState.FINISHED_ACTION) {
                        onWaypointReach(progress.targetWaypointIndex)
                    }
                }

                override fun onExecutionFinish(p0: DJIError?) {
                    logger.d { "onExecutionFinish: $p0" }
                    onFinish(p0?.description)
                }
            }

        )
    }
}
