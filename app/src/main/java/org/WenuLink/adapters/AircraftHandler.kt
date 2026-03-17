package org.WenuLink.adapters

import com.MAVLink.enums.MAV_MODE_FLAG
import io.getstream.log.taggedLogger
import kotlin.getValue
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
    val cameras = CameraHandler.getInstance()
    var hasCameras: Boolean = false

    private val _isArmed = MutableStateFlow(false)
    val isArmed: StateFlow<Boolean> = _isArmed.asStateFlow()

    private val _isFlying = MutableStateFlow(false)
    val isFlying: StateFlow<Boolean> = _isFlying.asStateFlow()

    fun updateModeIfArmed(newMode: ArduCopterFlightMode? = null) {
        if (newMode != null) copterFlightMode = newMode
        baseMode = copterFlightMode.baseMode
        if (state.isArmed()) {
            baseMode = baseMode or MAV_MODE_FLAG.MAV_MODE_FLAG_SAFETY_ARMED
        }
    }

    fun modeTransition(copterMode: Long, onResult: (String?) -> Unit) {
        val newMode = ArduCopterFlightMode.from(copterMode)
        if (newMode == null) {
            onResult("No flight mode $copterMode found")
            return
        }
        modeTransition(newMode, onResult)
    }

    fun modeTransition(newMode: ArduCopterFlightMode, onResult: (String?) -> Unit) {
        if (newMode == copterFlightMode) { // Nothing to change
            updateModeIfArmed()
            onResult(null)
            return
        }

        logger.d { "Mode transition: $copterFlightMode->$newMode" }
        // TODO: check if transition conditions are met

        updateModeIfArmed(newMode)
        onResult(null)

        // Performs actions if needed
        if (newMode == ArduCopterFlightMode.LAND && state.isArmed()) land()

        if (newMode == ArduCopterFlightMode.RTL && state.isArmed()) doGoHome()

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
        // TODO: validate for successful transition, set previous mode if fail
    }

    fun stateTransition(state: AircraftState) {
        this.state.dispatch(state)
        updateModeIfArmed()
    }

    fun readCurrentState(): Boolean {
        // TODO: read components metadata

        // Update aircraft state if in-flight initialization
        state.homeSet(homeCoordinates != null)
        // System arm?
        if (FCManager.areMotorsArmed()) {
            armMotors(true) // Not allowed but required to update the StateFlow
            stateTransition(AircraftState.Arm) // Ensure logic state transition
        }
        // Already flying?
        if (FCManager.isFlying()) {
            takeOff() // StateFlow update
            stateTransition(AircraftState.InAir) // State transition
        } else {
            stateTransition(AircraftState.OnGround)
        }

        manualControl() // Initialize with Stabilize mode

        return true
    }

    fun processUserPreferences(): Boolean {
        // TODO: process user preferences (flight speed, RTL altitude, take off altitude)
        return true
    }

    suspend fun checkSensors(): Boolean {
        logger.i { "Waiting sensors data" }
        sensorsOk = AsyncUtils.waitTimeout(100L, 10000L, isReady = telemetry::isReadingSensors)
        if (!sensorsOk) {
            logger.e { "No sensor readings!" }
            return sensorsOk
        }

        // https://developer.dji.com/api-reference/android-api/Components/Compass/DJICompass.html
        val compassOk = telemetry.isCompassOk()
        if (!compassOk) logger.e { "Compass error!" }
        sensorsOk = sensorsOk && compassOk

        // https://developer.dji.com/api-reference/android-api/Components/IMUState/DJIIMUState.html
        val imuState: IMUState? = if (telemetry.isReadingSensors()) {
            telemetry.getIMUState()
        } else {
            null
        }

        val accOk = imuState?.accelerometer?.all { it == SensorState.OK } ?: false
        if (!accOk) logger.e { "Accelerometer error!" }
        sensorsOk = sensorsOk && accOk

        val gyroOk = imuState?.gyroscope?.all { it == SensorState.OK } ?: false
        if (!gyroOk) logger.e { "Gyroscope error!" }
        sensorsOk = sensorsOk && gyroOk

        hasCameras = cameras.initCameras()

        return sensorsOk
    }

    // check for home location and set
    fun updateHomeCoordinatesFromAircraft(): Boolean {
        // Ask for home position
        logger.d { "Requesting home coordinates update with current aircraft's location." }
        FCManager.setHomePosition { error ->
            if (error == null) {
                state.homeSet(homeCoordinates != null)
            } else {
                logger.w { "Error request: $error" }
            }
        }
        return state.isHomeSet()
    }

    suspend fun waitHomeUpdate() {
        AsyncUtils.waitReady(intervalTime = 1000L, isReady = ::updateHomeCoordinatesFromAircraft)
        logger.d { "Home updated: $homeCoordinates" }
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

        if (!processUserPreferences()) return
        logger.d { "\tLoading preferences: TBD" }

        if (!startTelemetry(5000L)) return
        logger.d { "\tInit. telemetry: OK" }

        if (!checkSensors()) return
        logger.d { "\tSensors status: OK" }

        if (!readCurrentState()) return
        logger.d { "\tReading FC state: OK" }

        preArmCheckOk = true
        isPowerOff = false
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

    fun manualControl() {
        controlTransition(ControlAuthority.REMOTE_CONTROLLER)
        modeTransition(ArduCopterFlightMode.STABILIZE) {
            // TODO: check and enforce mode transition
        }
    }

    suspend fun unload() {
        // Reverse boot sequence if needed
        stopTelemetry(500L)
        isPowerOff = true
    }

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
        logger.d { "Requesting Takeoff" }
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
        logger.d { "Requesting Land" }
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

    fun doGoHome() {
        controlTransition(ControlAuthority.TIMELINE_COMMAND)
        mission.doGoHome { error ->
            logger.i { "RTL: $error" }
            controlTransition(ControlAuthority.NONE)
        }
    }
}
