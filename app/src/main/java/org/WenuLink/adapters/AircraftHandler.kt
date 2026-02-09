package org.WenuLink.adapters

import com.MAVLink.enums.MAV_MODE_FLAG
import io.getstream.log.taggedLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.WenuLink.sdk.FCManager
import org.WenuLink.sdk.MissionActionManager
import org.WenuLink.sdk.MissionManager
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
    var state = AircraftStateMachine()
    var sensorsOk = false
        private set
    var preArmCheckOk = false
        private set
    private var controlAuthority = ControlAuthority.NONE
    var mission = MissionHandler.getInstance()
    val missionState: Int
        get() = mission.currentState

    private val _isArmed = MutableStateFlow(false)
    val isArmed: StateFlow<Boolean> = _isArmed.asStateFlow()

    private val _isFlying = MutableStateFlow(false)
    val isFlying: StateFlow<Boolean> = _isFlying.asStateFlow()


    fun baseModeFor(
        flightMode: ArduCopterFlightMode,
        armed: Boolean
    ): Int =
        flightMode2baseMode.getValue(flightMode) or
                if (armed) MAV_MODE_FLAG.MAV_MODE_FLAG_SAFETY_ARMED else 0

    fun modeTransition(copterMode: Long): Boolean {
        val newMode = ArduCopterFlightMode.from(copterMode)
        logger.d { "Transition: copterFlightMode=${copterFlightMode}->${newMode}" }
        if (newMode == null) return false
        // TODO: check if transition conditions are met
        // TODO: request for necessary internal changes
        updateModeIfArmed(newMode)

        // Performs actions if needed
        if (copterFlightMode == ArduCopterFlightMode.LAND && state.isArmed()) land()
        if (controlAuthority == ControlAuthority.WAYPOINT_MISSION &&
            copterFlightMode == ArduCopterFlightMode.AUTO &&
            mission.isMissionRunning
        ) mission.resume()

        return true
    }

    fun controlTransition(authority: ControlAuthority) {
        if (controlAuthority == ControlAuthority.WAYPOINT_MISSION) {
            // Decide policy: reject or stop mission
            MissionManager.stopMission {}
        }
        controlAuthority = authority
    }

    fun updateModeIfArmed(newMode: ArduCopterFlightMode? = null) {
        if (newMode != null) copterFlightMode = newMode
        baseMode = baseModeFor(copterFlightMode, state.isArmed())
    }

    fun stateTransition(state: AircraftState) {
        this.state.dispatch(state)
        updateModeIfArmed()
    }

    fun readCurrentState(): Boolean {
        // TODO: read components metadata
        return true
    }

    fun processUserPreferences(): Boolean {
        // TODO: process user preferences
        return true
    }

    fun enableFeatures(): Boolean {
        // TODO: cross check aircraft capabilities vs app features
        return true
    }

    fun checkSensors(): Boolean {
        stateTransition(AircraftState.Calibration)
        sensorsOk = true
        // TODO: check sensor calibration
        // https://developer.dji.com/api-reference/android-api/Components/Compass/DJICompass.html
        // https://developer.dji.com/api-reference/android-api/Components/IMUState/DJIIMUState.html
        // https://developer.dji.com/api-reference/android-api/Components/FlightController/DJIFlightController_DJIGravityCenterState.html
        return sensorsOk
    }

    // check for home location and set
    fun getHomePosition(): Coordinates3D? = FCManager.getHomePosition()

    fun updateHomePositionFromAircraft() {
        // Ask for home position
        logger.d { "Requesting home position update with aircraft's location." }
        FCManager.setHomePosition { error ->
            if (error == null) isHomeSet = true
            if (error != null) logger.w { "Unable to set home position: $error" }
        }
    }

    suspend fun waitForHomeLocation() {
        isHomeSet = false
        logger.d { "waitForHomeLocation" }
        while (!isHomeSet || getHomePosition() == null) {
            if (!isHomeSet) updateHomePositionFromAircraft()
            delay(1000L)
        }
        logger.d { "waitForHomeLocation: OK ${getHomePosition()}" }
    }

    suspend fun boot() {
        stateTransition(AircraftState.Boot)
        preArmCheckOk = false
        logger.d { "Aircraft booting..." }

        // TODO perform all required checks and aircraft's initialization
        // TODO: initialize from current flightControlState for correct reinitialization if needed
        if (!readCurrentState()) return
        logger.d { "\tLoading info: TBD" }
        if (!processUserPreferences()) return
        logger.d { "\tLoading preferences: TBD" }
        if (!enableFeatures()) return
        logger.d { "\tLoading capabilities: TBD" }
        if (!checkSensors()) return
        logger.d { "\tSensors status: TBD" }

        preArmCheckOk = true
        stateTransition(AircraftState.OnGround)
        logger.d { "Aircraft boot: OK" }

        waitForHomeLocation()

        standby()  // possibly must be threaded for waiting standby mode
    }

    fun standby() {
        if (!preArmCheckOk) {
            logger.i { "Aircraft is not ready, prearm check failed" }
            return // only standby after prearm check condition ok
        }

        if (!state.isOnTheGround()) {
            logger.i { "Unable to do, aircraft is not on the ground" }
            return // only standby on ground
        }

        logger.i { "Aircraft is standby" }
        // All seems ok
        stateTransition(AircraftState.Standby)
    }

    fun registerHandlerScope(handlerScope: CoroutineScope) {
        isArmed.distinctUntilChangedBy { it }
            .onEach {
                if (it) fcArmMotors()
                else fcDisarmMotors()
            }
            .launchIn(handlerScope)

        isFlying.distinctUntilChangedBy { it }
            .onEach {
                if (it) fcTakeOff()
                else fcLanding()
            }
            .launchIn(handlerScope)

        registerMissionListeners(handlerScope)
    }

    private fun registerMissionListeners(handlerScope: CoroutineScope) {
        mission.registerListeners(
            onStart = {
                handlerScope.launch {
                    logger.d { "Mission started" }
                    controlTransition(ControlAuthority.WAYPOINT_MISSION)
                    // Must validate to which state must transit, assumes that starts from the ground
                    // Possibly change to waitAltitude
                    waitLandedStateTransition(true)
                    logger.d { "MissionManager state: ${MissionManager.currentState}" }
                }
            },
            onWaypointReach = { index ->
                handlerScope.launch {
                    logger.d { "Waypoint reached" }

                    if (index == 1 && controlAuthority == ControlAuthority.WAYPOINT_MISSION)
                    // Call pause only for second element, assumes 0 = arm, 1 = takeoff/initial alt.
                        mission.pause()

                    // Wait for AUTO mode transition
//                    fun isAutoMode() = copterFlightMode == ArduCopterFlightMode.AUTO
//                    AsyncUtils.waitReady(100L, ::isAutoMode)
//                    mission.resume()
                }
            },
            onFinish = { error ->
                handlerScope.launch {
                    logger.d { "Mission finish" }
                    if (error != null) {
                        logger.i { "Mission finished with error: $error" }
                        return@launch
                    }

                    logger.d { "MissionManager state: ${MissionManager.currentState}" }
                    controlTransition(ControlAuthority.NONE)
                }
            }
        )

        MissionActionManager.registerGoHomeFinished {
            handlerScope.launch {
                // This assumes RTL ends with landing confirmation
                waitLandingConfirmation()
                waitLandedStateTransition(false)
            }
        }

        MissionActionManager.startListener {
            // onError
            logger.e { "Action failed: $it" }
            controlAuthority = ControlAuthority.NONE
        }
    }

    fun armMotors(mustArm: Boolean) {
        _isArmed.value = mustArm
    }

    suspend fun waitArmTransition(mustArm: Boolean): Boolean {
        fun areMotorsUpdated() = if (mustArm) FCManager.areMotorsArmed() else !FCManager.areMotorsArmed()
        val motorsUpdated = AsyncUtils.waitTimeout(isReady = ::areMotorsUpdated)
        logger.d { "Motors ${if (mustArm) "armed" else "disarmed"}: $motorsUpdated" }

        if (mustArm && FCManager.areMotorsArmed()) {
            stateTransition(AircraftState.Arm)
        }

        if (!mustArm && !FCManager.areMotorsArmed()) {
            standby()
        }

        logger.i {
            "Aircraft (isArmed=${FCManager.areMotorsArmed()}) (isFlying=${FCManager.isFlying()})"
        }

        return motorsUpdated
    }

    suspend fun fcArmMotors() {
        if (!state.isStandBy()){
            logger.i { "Unable to arm, aircraft is not ready" }
            return // only arm from standby
        }
        if (!state.isOnTheGround()) {
            logger.i { "Invalid arm motors call, aircraft is not on the ground" }
            return // only arm from the ground
        }
        logger.d { "Arming motors" }
        // TODO: update according to each mode
        // https://ardupilot.org/copter/docs/arming_the_motors.html
        if (copterFlightMode == ArduCopterFlightMode.STABILIZE) {
            // Manual takeoff
            FCManager.armMotors()
            waitArmTransition(true)
        }
        else if (copterFlightMode == ArduCopterFlightMode.GUIDED)// automatic takeoff
            stateTransition(AircraftState.Arm)
    }

    suspend fun fcDisarmMotors() {
        if (state.isStandBy()) return
        logger.d { "Disarming motors" }

        if (state.isFlying()) {
            logger.w { "Invalid disarm motors call, aircraft still flying" }
            return // only arm from the ground
        }
        FCManager.disarmMotors()
        waitArmTransition(false)
    }

    fun takeOff() {
        _isFlying.value = true
    }

    fun getCurrentCoordinates(): Coordinates3D? {
        logger.d { "getCurrentCoordinates" }
        val takeoffAltitude = FCManager.fcInstance?.state?.takeoffLocationAltitude
        val location = FCManager.fcInstance?.state?.aircraftLocation ?: return null
        logger.d { "getCurrentCoordinates: currentAltitude: ${location.altitude}, takeoffAltitude: $takeoffAltitude" }
        return Coordinates3D(location.longitude, location.latitude, location.altitude)
    }

    suspend fun waitLandedStateTransition(takingOff: Boolean): Boolean {
        logger.d { "Waiting for ${if (takingOff) "taking off" else "touching ground"}" }
        fun isFlyingConditioned() = if (takingOff) FCManager.isFlying() else !FCManager.isFlying()
        AsyncUtils.waitReady( 100L, isReady = ::isFlyingConditioned)

        if (takingOff && FCManager.isFlying()) {
            stateTransition(AircraftState.InAir)
            logger.d { "Aircraft is flying" }
        }

        if (!takingOff && !FCManager.isFlying()) {
            stateTransition(AircraftState.OnGround)
            logger.d { "Aircraft is on the ground" }
            armMotors(false)
        }

        logger.i {
            "Aircraft (isArmed=${FCManager.areMotorsArmed()}) (isFlying=${FCManager.isFlying()})"
        }

        return isFlyingConditioned()
    }

    suspend fun fcTakeOff() {
        if (!state.isArmed()) return // need to arm first

        if (!state.isOnTheGround()) return // need to be on the ground

        logger.d { "Aircraft is taking off"}
        FCManager.startTakeoff()
        stateTransition(AircraftState.Takeoff)

        if (waitLandedStateTransition(true)) {
            logger.d { "\tsuccess" }
        }
        else {
            logger.d { "\terror, disarming" }
            armMotors(false)
        }
    }

    fun land() {
        _isFlying.value = false
    }

    suspend fun waitLandingConfirmation() {
        // https://developer.dji.com/api-reference/android-api/Components/FlightController/DJIFlightController.html#djiflightcontroller_confirmlanding_inline
        logger.d { "\tWaiting altitude of 0.3m" }
        AsyncUtils.waitReady(100L, FCManager::needLandingConfirmation)

        FCManager.confirmLanding {
            logger.d { "\tLanding confirm"}
        }
    }

    suspend fun fcLanding() {
        // need to be armed or flying
        if (!state.isArmed()) {
            logger.d { "Invalid call, aircraft is not armed" }
            return
        }

        // need to be flying
        if (!state.isFlying()) {
            logger.d { "Invalid landing call, aircraft is not flying" }
            return
        }

        logger.d { "Aircraft is landing"}
//        FCManager.startLanding()
        stateTransition(AircraftState.Land)
        doLand()

        // Landing confirmation when 0.3 altitude is reached
//        waitLandingConfirmation()

        if (waitLandedStateTransition(false)) {
            logger.d { "\ton the ground"}
        }
        else logger.d { "\terror" }

    }

    fun doReposition(
        target: Coordinates3D,
        speed: Float?
    ) {
        controlTransition(ControlAuthority.TIMELINE_COMMAND)
        mission.doReposition(target, speed ?: mission.flightSpeed) { error ->
            logger.i { "Reposition completed: $error" }
            controlAuthority = ControlAuthority.NONE
        }
    }

    fun doLand() {
        controlTransition(ControlAuthority.TIMELINE_COMMAND)
        mission.doLand { error ->
            logger.i { "Landedd:$error" }
            controlAuthority = ControlAuthority.NONE
        }
    }
}
