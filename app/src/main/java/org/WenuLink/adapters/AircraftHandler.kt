package org.WenuLink.adapters

import com.MAVLink.enums.MAV_LANDED_STATE
import com.MAVLink.enums.MAV_MODE_FLAG
import com.MAVLink.enums.MAV_STATE
import io.getstream.log.taggedLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.WenuLink.sdk.FCManager
import kotlin.getValue

class AircraftHandler {
    companion object {
        private var mInstance: AircraftHandler? = null

        fun getInstance(): AircraftHandler {
            if (mInstance == null)
                mInstance = AircraftHandler()
            return mInstance!!
        }

    }

    private val logger by taggedLogger("AircraftHandler")
    val startTimestamp: Long = System.currentTimeMillis()
    val systemBootTime: Long
        get() { return System.currentTimeMillis() - startTimestamp }
    var isHomeSet = false
        private set
    var baseMode: Int = MAV_MODE_FLAG.MAV_MODE_FLAG_CUSTOM_MODE_ENABLED
        private set
    var customMode = ArduCopterFlightMode.STABILIZE
        private set
    var mavlinkState: Int = MAV_STATE.MAV_STATE_UNINIT
        private set
    var landedState: Int = MAV_LANDED_STATE.MAV_LANDED_STATE_UNDEFINED
        private set
    var sensorsOk = false
        private set
    var preArmCheckOk = false
        private set
    var systemStandby = false
        private set
    var systemArmed = false
        private set

    private val _isArmed = MutableStateFlow(false)
    val isArmed: StateFlow<Boolean> = _isArmed.asStateFlow()

    private val _isFlying = MutableStateFlow(false)
    val isFlying: StateFlow<Boolean> = _isFlying.asStateFlow()

    private val _isLanding = MutableStateFlow(false)
    val isLanding: StateFlow<Boolean> = _isLanding.asStateFlow()

    fun baseModeFor(
        flightMode: ArduCopterFlightMode,
        armed: Boolean
    ): Int =
        flightMode2baseMode.getValue(flightMode) or
                if (armed) MAV_MODE_FLAG.MAV_MODE_FLAG_SAFETY_ARMED else 0

    fun modeTransition(copterMode: Long): Boolean {
        val newMode = ArduCopterFlightMode.from(copterMode)
        logger.d { "Transition: customMode=$customMode->${newMode}" }
        if (newMode == null) return false
        // TODO: check if transition conditions are met
        // TODO: request for necessary internal changes
        customMode = newMode
        baseMode = baseModeFor(newMode, systemArmed)
        return true
    }

    fun updateModeIfArmed() {
        baseMode = baseModeFor(customMode, systemArmed)
    }

    fun readComponentsData(): Boolean {
        // TODO: read components metadata
        return true
    }

    fun processUserPreferences(): Boolean {
        // TODO: process user preferences
        return true
    }

    fun checkCompatibility(): Boolean {
        // TODO: cross check aircraft capabilities vs app features
        return true
    }

    fun checkSensors(): Boolean {
        mavlinkState = MAV_STATE.MAV_STATE_CALIBRATING
        sensorsOk = true
        // TODO: check sensor calibration
        // https://developer.dji.com/api-reference/android-api/Components/Compass/DJICompass.html
        // https://developer.dji.com/api-reference/android-api/Components/IMUState/DJIIMUState.html
        // https://developer.dji.com/api-reference/android-api/Components/FlightController/DJIFlightController_DJIGravityCenterState.html
        return sensorsOk
    }

    // check for home location and set
    fun getHomePosition(): Coordinates3D? = FCManager.getHomePosition()

    fun updateHomePositionFromAircraft(): Coordinates3D? {
        // Ask for home position
        logger.d { "Requesting home position update with aircraft's location." }
        FCManager.setHomePosition { error ->
            if (error == null) isHomeSet = true
            if (error != null) logger.w { "Unable to set home position: $error" }
        }
        return getHomePosition()
    }

    suspend fun waitForHomeLocation() {
        isHomeSet = false
        logger.d { "waitForHomeLocation" }
        while (updateHomePositionFromAircraft() == null) {
            delay(1000L)
        }
        isHomeSet = getHomePosition() != null
        logger.d { "waitForHomeLocation: OK ${getHomePosition()}" }
    }

    suspend fun boot() {
        mavlinkState = MAV_STATE.MAV_STATE_BOOT
        preArmCheckOk = false

        // TODO perform all required checks and aircraft's initialization
        if (!readComponentsData()) return
        if (!processUserPreferences()) return
        if (!checkCompatibility()) return
        if (!checkSensors()) return

        preArmCheckOk = true

        waitForHomeLocation()

        standby()
    }

    fun standby() {
        // All seems ok
        mavlinkState = MAV_STATE.MAV_STATE_STANDBY
        landedState = MAV_LANDED_STATE.MAV_LANDED_STATE_ON_GROUND
        systemStandby = true
        systemArmed = false
        updateModeIfArmed()
    }

    fun registerHandlerScope(handlerScope: CoroutineScope) {
        isArmed.distinctUntilChangedBy { it }
            .onEach { fcArmMotors(it) }
            .launchIn(handlerScope)

        isFlying.distinctUntilChangedBy { it }
            .onEach { if (it) fcTakeOff() }
            .launchIn(handlerScope)

        isLanding.distinctUntilChangedBy { it }
            .onEach { if (it) fcLanding() }
            .launchIn(handlerScope)
    }

    fun armMotors(mustArm: Boolean) {
        _isArmed.value = mustArm
    }

    fun fcArmMotors(mustArm: Boolean) {
        logger.d { "Arming motors: $mustArm" }

        if (mavlinkState != MAV_STATE.MAV_STATE_STANDBY){
            logger.w { "Not ready to arm, current state $mavlinkState != ${MAV_STATE.MAV_STATE_STANDBY}" }
            return // only arm from standby
        }
//        val motorsArmed = if (mustArm) FCManager.armMotors() else FCManager.disarmMotors()

        // takeoff function deals with the aircraft arm state
        if (mustArm) {
            mavlinkState = MAV_STATE.MAV_STATE_ACTIVE
            systemStandby = false
            systemArmed = true
            updateModeIfArmed()
        }
        else standby()

        logger.d {
            "FC state: isArmed=${FCManager.areMotorsArmed()}, isFlying=${FCManager.isFlying()}"
        }
    }

    fun takeOff() {
        _isFlying.value = true
        _isLanding.value = false
    }

    fun getCurrentCoordinates(): Coordinates3D? {
        logger.d { "getCurrentCoordinates" }
        val takeoffAltitude = FCManager.fcInstance?.state?.takeoffLocationAltitude
        val location = FCManager.fcInstance?.state?.aircraftLocation ?: return null
        logger.d { "getCurrentCoordinates: currentAltitude: ${location.altitude}, takeoffAltitude: $takeoffAltitude" }
        return Coordinates3D(location.longitude, location.latitude, location.altitude)
    }

    suspend fun fcTakeOff() {
        // TODO: verify or enforce arm first?
        if (mavlinkState != MAV_STATE.MAV_STATE_ACTIVE) return // need to arm first

        logger.d { "Taking off" }
        landedState = MAV_LANDED_STATE.MAV_LANDED_STATE_TAKEOFF

        val isFlying = FCManager.simpleTakeoff()

        var readings = 10
        while (readings > 0) {
            readings--
            logger.d { "Current Coordinates: $systemBootTime -> ${getCurrentCoordinates()}" }
            delay(500L)
        }

        logger.d {
            "FC state: isArmed=${FCManager.areMotorsArmed()}, isFlying=${FCManager.isFlying()}"
        }

        if (FCManager.isFlying()) {
            landedState = MAV_LANDED_STATE.MAV_LANDED_STATE_IN_AIR
            logger.i { "Take off successfully" }
        }
        else {
            logger.e { "Error in taking off" }
//            fcArmMotors(false)
        }
    }

    fun landing() {
        _isFlying.value = false
        _isLanding.value = true
    }

    suspend fun fcLanding() {
        if (mavlinkState != MAV_STATE.MAV_STATE_ACTIVE) return // need to be armed or flying

        mavlinkState = MAV_STATE.MAV_STATE_FLIGHT_TERMINATION
        landedState = MAV_LANDED_STATE.MAV_LANDED_STATE_LANDING
        val isFlying = FCManager.simpleLanding()
    }


    fun flyTo(handlerScope: CoroutineScope) {
        TODO("new method")
    }

    fun returnToLaunch(handlerScope: CoroutineScope) {
        TODO("new method")
    }

    // TODO: all movement methods
}
