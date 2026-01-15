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
    var copterFlightMode = ArduCopterFlightMode.STABILIZE
        private set
    var mavlinkState: Int = MAV_STATE.MAV_STATE_UNINIT
        private set
    var landedState: Int = MAV_LANDED_STATE.MAV_LANDED_STATE_UNDEFINED
        private set
    var sensorsOk = false
        private set
    var preArmCheckOk = false
        private set
    val systemStandby: Boolean
        get() { return mavlinkState == MAV_STATE.MAV_STATE_STANDBY }
    val systemArmed: Boolean
        get() { return mavlinkState == MAV_STATE.MAV_STATE_ACTIVE }
    val systemFlying: Boolean
        get() { return landedState == MAV_LANDED_STATE.MAV_LANDED_STATE_IN_AIR }
    val systemOnGround: Boolean
        get() { return landedState == MAV_LANDED_STATE.MAV_LANDED_STATE_ON_GROUND }

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
        logger.d { "Transition: customMode=${copterFlightMode}->${newMode}" }
        if (newMode == null) return false
        // TODO: check if transition conditions are met
        // TODO: request for necessary internal changes
        updateModeIfArmed(newMode)

        // Performs actions if needed
        if (copterFlightMode == ArduCopterFlightMode.LAND && systemArmed) land()

        return true
    }

    fun updateModeIfArmed(newMode: ArduCopterFlightMode? = null) {
        if (newMode != null) copterFlightMode = newMode
        baseMode = baseModeFor(copterFlightMode, systemArmed)
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
        logger.d { "Aircraft booting..." }

        // TODO perform all required checks and aircraft's initialization
        if (!readComponentsData()) return
        logger.d { "\tLoading info: TBD" }
        if (!processUserPreferences()) return
        logger.d { "\tLoading preferences: TBD" }
        if (!checkCompatibility()) return
        logger.d { "\tLoading capabilities: TBD" }
        if (!checkSensors()) return
        logger.d { "\tSensors status: YBD" }

        preArmCheckOk = true
        landedState = MAV_LANDED_STATE.MAV_LANDED_STATE_ON_GROUND // assumes it boots from the ground
        logger.d { "Aircraft boot: OK" }

        waitForHomeLocation()

        standby()
    }

    fun standby() {
        if (!preArmCheckOk) {
            logger.i { "Aircraft is not ready, prearm check failed" }
            return // only standby after prearm check condition ok
        }

        if (!systemOnGround) {
            logger.i { "Unable to do, aircraft is not on the ground" }
            return // only standby on ground
        }

        logger.i { "Aircraft is standby" }
        // All seems ok
        mavlinkState = MAV_STATE.MAV_STATE_STANDBY
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

    suspend fun fcArmMotors(mustArm: Boolean) {
        logger.i { "Arming motors: $mustArm" }
        if (mustArm) {
            if (!systemStandby){
                logger.i { "Unable to arm, aircraft is not ready" }
                return // only arm from standby
            }
            if (!systemOnGround) {
                logger.i { "Invalid arm motors call, aircraft is not on the ground" }
                return // only arm from the ground
            }

            // will attempt to arm motors only if this is present mode
            // TODO: update according to each mode
            // https://ardupilot.org/copter/docs/arming_the_motors.html
            var armed = copterFlightMode == ArduCopterFlightMode.GUIDED  // mandatory for arm_disarm message using automatic takeoff
            if (copterFlightMode == ArduCopterFlightMode.STABILIZE)  // will actually arm the motors first
                armed = FCManager.armMotors()

            if (!armed) return

            mavlinkState = MAV_STATE.MAV_STATE_ACTIVE
            updateModeIfArmed()

        }
        else {
            if (FCManager.disarmMotors()) standby()
        }

        logger.i {
            "Aircraft (isArmed=${FCManager.areMotorsArmed()}) (isFlying=${FCManager.isFlying()})"
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
        if (!systemArmed) return // need to arm first

        logger.d { "Aircraft is taking off"}
        landedState = MAV_LANDED_STATE.MAV_LANDED_STATE_TAKEOFF

        if (FCManager.simpleTakeoff()) {
            landedState = MAV_LANDED_STATE.MAV_LANDED_STATE_IN_AIR
            logger.d { "\t success" }
        }
        else {
            logger.d { "\t error, disarming" }
            armMotors(false)
        }

        logger.i {
            "Aircraft (isArmed=${FCManager.areMotorsArmed()}) (isFlying=${FCManager.isFlying()})"
        }
    }

    fun land() {
        _isFlying.value = false
        _isLanding.value = true
    }

    suspend fun fcLanding() {
        // need to be armed or flying
        if (!systemArmed) {
            logger.i { "Not ready to land, current state $mavlinkState != ${MAV_STATE.MAV_STATE_ACTIVE}" }
            return
        }

        // need to be in air
        if (!systemFlying) {
            logger.i { "Invalid landing call, aircraft is not in air" }
            return
        }

        mavlinkState = MAV_STATE.MAV_STATE_FLIGHT_TERMINATION
        landedState = MAV_LANDED_STATE.MAV_LANDED_STATE_LANDING

        logger.d { "Aircraft is landing"}
        if (FCManager.simpleLanding()) {
            landedState = MAV_LANDED_STATE.MAV_LANDED_STATE_ON_GROUND
            logger.d { "\ton the ground"}
            armMotors(false)
        }
        else logger.d { "\terror" }

        logger.i {
            "Aircraft (isArmed=${FCManager.areMotorsArmed()}) (isFlying=${FCManager.isFlying()})"
        }
    }


    fun flyTo(handlerScope: CoroutineScope) {
        TODO("new method")
    }

    fun returnToLaunch(handlerScope: CoroutineScope) {
        TODO("new method")
    }

    // TODO: all movement methods
}
