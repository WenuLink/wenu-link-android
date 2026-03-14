package org.WenuLink.adapters

import com.MAVLink.enums.MAV_MODE_FLAG
import io.getstream.log.taggedLogger
import kotlinx.coroutines.CoroutineScope
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

class AircraftHandler {
    companion object {
        private var mInstance: AircraftHandler? = null

        fun getInstance(serviceScope: CoroutineScope? = null): AircraftHandler {
            if (mInstance == null) {
                mInstance = AircraftHandler()
            }
            if (serviceScope != null) {
                mInstance!!.registerHandlerScope(serviceScope)
            }

            return mInstance!!
        }
    }

    private val logger by taggedLogger(AircraftHandler::class.java.simpleName)
    val startTimestamp: Long = System.currentTimeMillis()
    val systemBootTime: Long get() = System.currentTimeMillis() - startTimestamp
    var baseMode: Int = MAV_MODE_FLAG.MAV_MODE_FLAG_CUSTOM_MODE_ENABLED
        private set
    var copterFlightMode = ArduCopterFlightMode.STABILIZE
        private set
    val state = AircraftStateMachine()
    var sensorsOk = false
        private set
    var preArmCheckOk = false
        private set
    val mission = MissionHandler.getInstance()
    val telemetry = TelemetryHandler.getInstance()
    val homeCoordinates: Coordinates3D?
        get() = FCManager.getHomePosition()
    var isPowerOff = false
    val rcInput: RCData?
        get() = telemetry.getRCData()

    private val _isArmed = MutableStateFlow(false)
    val isArmed: StateFlow<Boolean> = _isArmed.asStateFlow()

    private val _isFlying = MutableStateFlow(false)
    val isFlying: StateFlow<Boolean> = _isFlying.asStateFlow()

    fun baseModeFor(flightMode: ArduCopterFlightMode, armed: Boolean): Int {
        val base = flightMode2baseMode.getValue(flightMode)
        val armedFlag = if (armed) MAV_MODE_FLAG.MAV_MODE_FLAG_SAFETY_ARMED else 0

        return base or armedFlag
    }

    fun modeTransition(copterMode: Long): Boolean {
        val newMode = ArduCopterFlightMode.from(copterMode) ?: return false
        if (newMode == copterFlightMode) return true
        logger.d { "Mode transition: $copterFlightMode->$newMode" }
        // TODO: check if transition conditions are met
        // TODO: request for necessary internal changes
        // TODO: validate for successful transition

        // Performs actions if needed
        if (newMode == ArduCopterFlightMode.LAND && state.isArmed()) land()

        if (newMode == ArduCopterFlightMode.AUTO &&
            state.isMissionWaypoint() &&
            mission.isMissionRunning
        ) {
            mission.resumeWaypoint()
        }

        if (newMode == ArduCopterFlightMode.BRAKE &&
            state.isMissionWaypoint() &&
            mission.isMissionRunning
        ) {
            mission.pauseWaypoint()
        }

        if (newMode == ArduCopterFlightMode.AUTO && state.isTimelineCommand()) {
            mission.resumeCommand()
        }

        if (newMode == ArduCopterFlightMode.BRAKE && state.isTimelineCommand()) {
            mission.pauseCommand()
        }

        updateModeIfArmed(newMode)

        return true
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
        // TODO: initialize state from current flightControlState for correct reinitialization if needed
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
    fun updateHomeCoordinatesFromAircraft(): Coordinates3D? {
        // Ask for home position
        logger.d { "Requesting home coordinates update with current aircraft's location." }
        FCManager.setHomePosition { error ->
            if (error == null) {
                state.homeSet(true)
            } else {
                logger.w { "Error request: $error" }
            }
        }
        return homeCoordinates
    }

    suspend fun waitHomeUpdate() {
        state.homeSet(false)
        fun isHomeUpdated() = updateHomeCoordinatesFromAircraft() != null
        AsyncUtils.waitReady(intervalTime = 1000L, isReady = ::isHomeUpdated)
        logger.d { "Home coordinates: $homeCoordinates" }
    }

    suspend fun waitHome() {
        AsyncUtils.waitReady(intervalTime = 1000L, isReady = state::isHomeSet)
        logger.d { "Home coordinates: $homeCoordinates" }
    }

    suspend fun startTelemetry(timeout: Long = 5000L): Boolean {
        // Start telemetry process
        telemetry.launchTelemetry(true)
        // Second wait to receive the data ready for broadcast
        return waitTelemetry(timeout)
    }

    suspend fun stopTelemetry(delay: Long = 1000L): Boolean {
        // Stop telemetry process
        telemetry.launchTelemetry(false)
        // Second wait to remove the existing data
        return telemetry.waitDataRemoving(delay)
    }

    suspend fun waitTelemetry(timeout: Long = 5000L): Boolean = telemetry.waitDataReading(timeout)

    suspend fun boot() {
        stateTransition(AircraftState.Boot)
        preArmCheckOk = false
        logger.d { "Aircraft booting..." }

        // TODO perform all required checks and aircraft's initialization
        if (!processUserPreferences()) return
        logger.d { "\tLoading preferences: TBD" }
        if (!enableFeatures()) return
        logger.d { "\tLoading capabilities: TBD" }
        if (!checkSensors()) return
        logger.d { "\tSensors status: TBD" }

        if (!startTelemetry(5000L)) return
        logger.d { "\tInit. telemetry: OK" }

        if (!readCurrentState()) return
        logger.d { "\tReading FC state: TBD" }

        preArmCheckOk = true
        isPowerOff = false

        stateTransition(AircraftState.OnGround)
        logger.d { "Aircraft boot: OK" }

        waitHomeUpdate()

        standby() // possibly must be threaded for waiting standby mode
    }

    fun standby() {
        if (!preArmCheckOk) {
            logger.i { "Unable to standby, prearm check failed" }
            return // only standby after prearm check condition ok
        }

        if (!state.isOnTheGround()) {
            logger.i { "Unable to standby, aircraft not on the ground" }
            return // only standby on ground
        }

        logger.i { "Aircraft in standby" }
        // All seems ok
        stateTransition(AircraftState.Standby)
    }

    fun registerHandlerScope(handlerScope: CoroutineScope) {
        telemetry.registerHandlerScope(handlerScope)
//        mission.registerHandlerScope(this, handlerScope)
        registerMissionListeners(handlerScope)

        isArmed.distinctUntilChangedBy { it }
            .onEach {
                if (it) {
                    fcArmMotors()
                } else {
                    fcDisarmMotors()
                }
            }
            .launchIn(handlerScope)

        isFlying.distinctUntilChangedBy { it }
            .onEach {
                if (it) {
                    fcTakeOff()
                } else {
                    landing()
                }
            }
            .launchIn(handlerScope)
    }

    private fun registerMissionListeners(handlerScope: CoroutineScope) {
        mission.startListenersWaypoint(
            onStart = {
                handlerScope.launch {
                    logger.d { "Mission started" }
                    controlTransition(ControlAuthority.WAYPOINT_MISSION)
                    // Must validate to which state must transit, assumes that starts from the
                    // ground
                    // Possibly change to waitAltitude
                    waitLandedStateTransition(true)
                    logger.d { "MissionManager state: ${MissionManager.currentState}" }
                }
            },
            onWaypointReach = { index ->
                handlerScope.launch {
                    logger.d { "Waypoint reached" }

                    if (index == 0 && state.isMissionWaypoint()) {
                        // Call pause only for second element
                        // assumes 0 = arm, 1 = takeoff/initial alt.
                        mission.pauseWaypoint()
                    }

                    // Wait for AUTO mode transition
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
                waitAndConfirmLanding()
                waitLandedStateTransition(false)
            }
        }

        MissionActionManager.startListener {
            // onError
            logger.e { "Action failed: $it" }
            controlTransition(ControlAuthority.NONE)
        }
    }

    fun cancelMission() {
        logger.d { "cancel mission" }
        if (state.isMissionWaypoint()) mission.stopWaypoint()
        if (state.isTimelineCommand()) mission.cancelCommand()
    }

    fun controlTransition(authority: ControlAuthority) {
        // Decide policy: reject or stop mission
        if (!state.isNewControlAuthority(authority)) return
        cancelMission()
        logger.d { "Control transition: ${state.control}->$authority" }
        state.setControlAuthority(authority)
    }

    fun controlManual() {
        controlTransition(ControlAuthority.REMOTE_CONTROLLER)
        modeTransition(ArduCopterFlightMode.STABILIZE.mode)
    }

    suspend fun unload() {
        // Change state and control
        controlManual()
        // Reverse boot sequence if need
        stopTelemetry(500L)
        isPowerOff = true
    }

//    suspend fun shutdown() {
//        // TODO: perform RTL or LAND before?
//        // shutdown sequence cleaning operations and reversing boot()
//        if (state.isOnTheGround() && !state.isArmed()) {
//            stateTransition(AircraftState.PowerOff)
//        }
//        // Change state and reverse boot sequence
//        unload()
//        isPowerOff = true
//    }

    fun armMotors(mustArm: Boolean) {
        _isArmed.value = mustArm
    }

    suspend fun waitArmTransition(mustArm: Boolean): Boolean {
        fun motorsMatchTarget(): Boolean = mustArm == FCManager.areMotorsArmed()

        val motorsUpdated = AsyncUtils.waitTimeout(isReady = ::motorsMatchTarget)

        val armed = FCManager.areMotorsArmed()

        logger.d { "Motors ${if (mustArm) "armed" else "disarmed"}: $motorsUpdated" }

        if (mustArm && armed) {
            stateTransition(AircraftState.Arm)
        }

        if (!mustArm && !armed) {
            standby()
        }

        logger.i {
            "Aircraft (isArmed=$armed) (isFlying=${FCManager.isFlying()})"
        }

        return motorsUpdated
    }

    suspend fun fcArmMotors() {
        if (!state.isStandBy()) {
            logger.i { "Unable to arm, aircraft not ready" }
            return // only arm from standby
        }
        if (!state.isOnTheGround()) {
            logger.i { "Invalid arm motors call, aircraft not on the ground" }
            return // only arm from the ground
        }
        logger.d { "Arming motors" }
        // TODO: update according to each mode
        // https://ardupilot.org/copter/docs/arming_the_motors.html
        if (copterFlightMode == ArduCopterFlightMode.STABILIZE) {
            // Manual takeoff
            FCManager.armMotors()
            waitArmTransition(true)
        } else if (copterFlightMode == ArduCopterFlightMode.GUIDED) {
            // automatic takeoff
            stateTransition(AircraftState.Arm)
        }
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
        logger.d {
            "getCurrentCoordinates: currentAltitude: ${location.altitude}, " +
                "takeoffAltitude: $takeoffAltitude"
        }
        return Coordinates3D(location.longitude, location.latitude, location.altitude)
    }

    suspend fun waitLandedStateTransition(takingOff: Boolean): Boolean {
        logger.d { "Waiting for ${if (takingOff) "taking off" else "touching ground"}" }
        fun flyingMatchesTarget(): Boolean = takingOff == FCManager.isFlying()
        AsyncUtils.waitReady(100L, isReady = ::flyingMatchesTarget)

        val flying = FCManager.isFlying()

        if (takingOff && flying) {
            stateTransition(AircraftState.InAir)
            logger.d { "Aircraft flying" }
        }

        if (!takingOff && !flying) {
            stateTransition(AircraftState.OnGround)
            logger.d { "Aircraft on the ground" }
            armMotors(false)
        }

        logger.i {
            "Aircraft (isArmed=${FCManager.areMotorsArmed()}) (isFlying=$flying)"
        }

        return flyingMatchesTarget()
    }

    suspend fun fcTakeOff() {
        if (!state.isArmed()) return // need to arm first

        if (!state.isOnTheGround()) return // need to be on the ground

        logger.d { "Aircraft taking off" }
        FCManager.startTakeoff()
        stateTransition(AircraftState.Takeoff)

        if (waitLandedStateTransition(true)) {
            logger.d { "\tsuccess" }
        } else {
            logger.d { "\terror, disarming" }
            armMotors(false)
        }
    }

    suspend fun waitAndConfirmLanding() {
        // https://developer.dji.com/api-reference/android-api/Components/FlightController/DJIFlightController.html#djiflightcontroller_confirmlanding_inline
        logger.d { "\tWaiting altitude of 0.3m" }
        AsyncUtils.waitReady(100L, FCManager::needLandingConfirmation)

        FCManager.confirmLanding {
            logger.d { "\tLanding confirm" }
        }
    }

    suspend fun landing() {
        // need to be flying
        if (!state.isFlying()) {
            logger.d { "Invalid landing call, aircraft not flying" }
            return
        }

        logger.d { "Aircraft landing" }
        stateTransition(AircraftState.Land)
        doLand()

        if (waitLandedStateTransition(false)) {
            logger.d { "\ton the ground" }
        } else {
            logger.d { "\terror" }
        }
    }

    fun land() {
        _isFlying.value = false
    }

    fun doMission() {
        controlTransition(ControlAuthority.WAYPOINT_MISSION)
        mission.startWaypoint()
    }

    fun doReposition(target: Coordinates3D, speed: Float?) {
        controlTransition(ControlAuthority.TIMELINE_COMMAND)
        mission.doReposition(target, speed ?: mission.flightSpeed) { error ->
            logger.i { "Reposition completed: $error" }
            controlTransition(ControlAuthority.NONE)
        }
    }

    fun doLand() {
        controlTransition(ControlAuthority.TIMELINE_COMMAND)
        mission.doLand { error ->
            logger.i { "Landed:$error" }
            controlTransition(ControlAuthority.NONE)
        }
    }
}
