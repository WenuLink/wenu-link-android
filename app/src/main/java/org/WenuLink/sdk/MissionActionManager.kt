package org.WenuLink.sdk

import dji.common.error.DJIError
import dji.common.model.LocationCoordinate2D
import dji.sdk.mission.MissionControl
import dji.sdk.mission.timeline.TimelineElement
import dji.sdk.mission.timeline.TimelineEvent
import dji.sdk.mission.timeline.actions.AircraftYawAction
import dji.sdk.mission.timeline.actions.GimbalAttitudeAction
import dji.sdk.mission.timeline.actions.GoHomeAction
import dji.sdk.mission.timeline.actions.GoToAction
import dji.sdk.mission.timeline.actions.HotpointAction
import dji.sdk.mission.timeline.actions.LandAction
import dji.sdk.mission.timeline.actions.RecordVideoAction
import dji.sdk.mission.timeline.actions.ShootPhotoAction
import dji.sdk.mission.timeline.actions.TakeOffAction
import io.getstream.log.taggedLogger
import kotlin.reflect.KClass
import org.WenuLink.adapters.Coordinates3D

/**
 * class related to https://developer.dji.com/api-reference/android-api/Components/Missions/TimelineMission.html
 */
object MissionActionManager {

    data class ActionCallbackKey(
        val actionClass: KClass<out TimelineElement>,
        val event: TimelineEvent
    )

    private val logger by taggedLogger("MissionActionManager")

    private val missionControl: MissionControl
        get() = MissionControl.getInstance()

    private var listener: MissionControl.Listener? = null
    private val callbacks =
        mutableMapOf<ActionCallbackKey, MutableList<() -> Unit>>()

    // ---- Lifecycle ----
    fun clear() {
        missionControl.unscheduleEverything()
        stopListener()
    }

    fun start() {
        missionControl.startTimeline()
    }

    fun stop() {
        missionControl.stopTimeline()
    }

    fun pause() {
        missionControl.pauseTimeline()
    }

    fun resume() {
        missionControl.resumeTimeline()
    }

    // ---- Actions ----

    fun scheduleTakeOff(): DJIError? = missionControl.scheduleElement(TakeOffAction())

    fun scheduleGoTo(coordinates: Coordinates3D, speed: Float? = null): DJIError? {
        val action = GoToAction(
            LocationCoordinate2D(coordinates.lat, coordinates.long),
            coordinates.alt
        )

        speed?.let { action.flightSpeed = it }

        return missionControl.scheduleElement(action)
    }

    fun scheduleLand(autoConfirm: Boolean = true): DJIError? {
        val land = LandAction().apply {
            autoConfirmLandingEnabled = autoConfirm
        }
        return missionControl.scheduleElement(land)
    }

    fun scheduleGoHome(autoConfirm: Boolean = true): DJIError? {
        val goHome = GoHomeAction().apply {
            autoConfirmLandingEnabled = autoConfirm
        }
        return missionControl.scheduleElement(goHome)
    }

    // ---- Listener and callbacks ----

    private fun <T : TimelineElement> registerCallback(
        actionClass: KClass<T>,
        event: TimelineEvent,
        callback: () -> Unit
    ) {
        val key = ActionCallbackKey(actionClass, event)
        callbacks.getOrPut(key) { mutableListOf() }.add(callback)
    }

    fun onFinish(action: KClass<out TimelineElement>, callback: () -> Unit) =
        registerCallback(action, TimelineEvent.FINISHED, callback)

    fun registerTakeOffFinished(callback: () -> Unit) = onFinish(TakeOffAction::class, callback)

    fun registerGoToFinished(callback: () -> Unit) = onFinish(GoToAction::class, callback)

    fun registerLandFinished(callback: () -> Unit) = onFinish(LandAction::class, callback)

    fun registerAircraftYawFinished(callback: () -> Unit) =
        onFinish(AircraftYawAction::class, callback)

    fun registerGoHomeFinished(callback: () -> Unit) = onFinish(GoHomeAction::class, callback)

    fun registerHotpointFinished(callback: () -> Unit) = onFinish(HotpointAction::class, callback)

    fun registerGimbalAttitudeFinished(callback: () -> Unit) =
        onFinish(GimbalAttitudeAction::class, callback)

    fun registerRecordVideoFinished(callback: () -> Unit) =
        onFinish(RecordVideoAction::class, callback)

    fun registerShootPhotoFinished(callback: () -> Unit) =
        onFinish(ShootPhotoAction::class, callback)

    fun startListener(onError: (String) -> Unit = {}) {
        stopListener()

        listener = MissionControl.Listener { element, event, error ->
            if (error != null) {
                onError("Timeline error: ${error.description}")
                return@Listener
            }

            val key = ActionCallbackKey(element!!::class, event)
            callbacks[key]?.forEach { it.invoke() }

            if (event == TimelineEvent.STOPPED) {
                logger.i { "Timeline stopped" }
            }
        }

        missionControl.addListener(listener!!)
    }

    fun stopListener() {
        listener?.let { missionControl.removeListener(it) }
        listener = null
    }
}
