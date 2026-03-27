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
import org.WenuLink.adapters.aircraft.Coordinates3D

/**
 * class related to https://developer.dji.com/api-reference/android-api/Components/Missions/TimelineMission.html
 */
object MissionActionManager {

    data class ActionCallbackKey(
        val actionClass: KClass<out TimelineElement>,
        val event: TimelineEvent
    )

    private val logger by taggedLogger(MissionActionManager::class.java.simpleName)

    private val missionControl: MissionControl
        get() = MissionControl.getInstance()

    private var listener: MissionControl.Listener? = null
    private val callbacks =
        mutableMapOf<ActionCallbackKey, MutableList<() -> Unit>>()

    val isRunning get() = missionControl.isTimelineRunning

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

    fun schedule(element: TimelineElement) = missionControl.scheduleElement(element)

    // ---- Listener and callbacks ----

    fun <T : TimelineElement> registerCallback(
        actionClass: KClass<T>,
        event: TimelineEvent,
        callback: () -> Unit
    ) {
        val key = ActionCallbackKey(actionClass, event)
        callbacks.getOrPut(key) { mutableListOf() }.add(callback)
    }

    fun onFinish(action: KClass<out TimelineElement>, callback: () -> Unit) =
        registerCallback(action, TimelineEvent.FINISHED, callback)

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
