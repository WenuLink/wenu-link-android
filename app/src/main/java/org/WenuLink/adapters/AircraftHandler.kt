package org.WenuLink.adapters

import com.MAVLink.enums.MAV_MODE_FLAG
import io.getstream.log.taggedLogger
import kotlin.getValue
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.WenuLink.adapters.aircraft.AircraftCommand
import org.WenuLink.adapters.aircraft.BootCommand
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
    var sensorsHealthy = false
        private set
    val mission = MissionHandler.getInstance()
    val telemetry = TelemetryHandler.getInstance()
    val homeCoordinates: Coordinates3D?
        get() = FCManager.getHomePosition()
    var isPowerOff = false
    val rcInput: RCData?
        get() = telemetry.getRCData()
    private var commandJob: Job? = null
    private val commandChannel =
        Channel<Pair<AircraftCommand, (String?) -> Unit>>(capacity = Channel.UNLIMITED)

    @Volatile
    private var currentCommand: AircraftCommand? = null
    private var guardJob: Job? = null

    init {
        dispatchCommand(BootCommand(5000))
    }

    private fun startCommandProcessor(scope: CoroutineScope) {
        commandJob?.cancel()

        commandJob = scope.launch {
            for ((cmd, onResult) in commandChannel) {
                currentCommand = cmd

                try {
                    logger.d { "Executing: ${cmd::class.simpleName}" }

                    val error = cmd.validate(this@AircraftHandler)
                    if (error != null) {
                        onResult(error)
                        continue
                    }

                    cmd.execute(this@AircraftHandler, onResult)
                } catch (e: Exception) {
                    logger.e { "Command failed: ${cmd::class.simpleName} -> ${e.message}" }
                    onResult(e.message)
                } finally {
                    currentCommand = null
                }
            }
        }
    }

    fun dispatchCommand(cmd: AircraftCommand, onResult: (String?) -> Unit = {}) {
        commandChannel.trySend(cmd to onResult)
    }

    private fun setMode(mode: ArduCopterFlightMode) {
        copterFlightMode = mode
        syncBaseMode()
    }

    private fun syncBaseMode() {
        baseMode = copterFlightMode.baseMode
        if (state.isArmed()) {
            baseMode = baseMode or MAV_MODE_FLAG.MAV_MODE_FLAG_SAFETY_ARMED
        }
    }

    private fun isModeAllowed(mode: ArduCopterFlightMode): Boolean = when (mode) {
        ArduCopterFlightMode.GUIDED ->
            state.isFlying() || state.isStandBy()

        ArduCopterFlightMode.AUTO ->
            state.isFlying() && mission.isMissionRunning

        ArduCopterFlightMode.LAND,
        ArduCopterFlightMode.RTL ->
            state.isFlying()

        else -> true
    }

    fun requestMode(mode: ArduCopterFlightMode): Result<Unit> {
        if (mode == copterFlightMode) return Result.success(Unit)

        if (!isModeAllowed(mode)) {
            return Result.failure(
                IllegalStateException("Mode $mode not allowed from $copterFlightMode")
            )
        }

        logger.d { "Mode change: $copterFlightMode -> $mode" }

        setMode(mode)

        return Result.success(Unit)
    }

    private fun enforceModeConsistency() {
        if (!isModeAllowed(copterFlightMode)) {
            val fallbackMode = if (state.isFlying()) {
                ArduCopterFlightMode.GUIDED
            } else {
                ArduCopterFlightMode.STABILIZE
            }

            logger.w {
                "Mode $copterFlightMode invalid for state ${state.mavlink}, fallback to $fallbackMode"
            }

            setMode(fallbackMode)
        } else {
            syncBaseMode()
        }
    }

    fun applyState(event: AircraftState) {
        val result = state.dispatch(event)

        if (result.isFailure) {
            logger.w { "Invalid state transition: $event" }
            return
        }

        enforceModeConsistency()
    }

    fun syncState() {
        // TODO: read components metadata
        if (!isPowerOff) return

        // Check for home position
        state.homeSet(homeCoordinates != null)

        // Check for arm and flying conditions
        val fcState = state.resolveFrom(
            FCManager.areMotorsArmed(),
            FCManager.isFlying()
        )

        if (!state.hasStateChanged(fcState)) return

        logger.w { "State reconciliation: $state -> $fcState" }
        // Force new logic state update only when different
        state.forceSet(fcState)
    }

    fun processUserPreferences(): Boolean {
        // TODO: process user preferences (flight speed, RTL altitude, take off altitude)
        return true
    }

    suspend fun sensorChecks(timeout: Long = 10000L): Boolean {
        val perSensorTime = (timeout.toFloat() / 4).roundToLong()
        logger.i { "Waiting sensors data" }
        var sensorsOk = true
        if (!AsyncUtils.waitTimeout(100L, timeout, isReady = telemetry::isReadingSensors)) {
            logger.e { "No sensor readings!" }
            return false
        }

        // https://developer.dji.com/api-reference/android-api/Components/Compass/DJICompass.html
        val compassOk = AsyncUtils.waitTimeout(
            100L,
            perSensorTime,
            isReady = telemetry::isCompassOk
        )
        if (!compassOk) logger.e { "Compass error!" }
        sensorsOk = sensorsOk && compassOk

        // https://developer.dji.com/api-reference/android-api/Components/IMUState/DJIIMUState.html
        val accOk = AsyncUtils.waitTimeout(
            100L,
            perSensorTime,
            isReady = telemetry::isAccelerometerOk
        )
        if (!accOk) logger.e { "Accelerometer error!" }
        sensorsOk = sensorsOk && accOk

        val gyroOk = AsyncUtils.waitTimeout(100L, perSensorTime, isReady = telemetry::isGyroscopeOk)
        if (!gyroOk) logger.e { "Gyroscope error!" }
        sensorsOk = sensorsOk && gyroOk

        val homeSet = waitHomeSet(perSensorTime)
        if (!homeSet) logger.e { "Home Location not set!" }
        sensorsOk = sensorsOk && homeSet

        return sensorsOk
    }

    // check for home location and set
    fun updateHomeCoordinatesFromAircraft(): Boolean {
        // Ask for home position
        logger.d { "Requesting home coordinates update with current aircraft's location." }
        FCManager.setHomePosition { error ->
            if (error != null) {
                logger.w { "Error request: $error" }
            }
        }
        return state.isHomeSet()
    }

    suspend fun waitHomeSet(timeout: Long = 10000L): Boolean {
        if (state.isHomeSet()) return true

        return AsyncUtils.waitTimeout(
            100L,
            timeout,
            ::updateHomeCoordinatesFromAircraft
        )
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

    suspend fun boot(timeout: Long = 5000L) {
        applyState(BootAircraftState)
        sensorsHealthy = false
        logger.d { "Aircraft booting..." }

        if (!processUserPreferences()) return
        logger.d { "\tLoading preferences: TBD" }

        if (!startTelemetry(timeout)) return
        logger.d { "\tInit. telemetry: OK" }

        logger.d { "\tSensors healthy?: $sensorsHealthy" }

        // initial standby state is async, until then, manual control
        manualControl()

        logger.d { "Aircraft boot: OK" }
        isPowerOff = false
    }

    private fun startGuardLoop(serviceScope: CoroutineScope) {
        guardJob?.cancel()

        guardJob = serviceScope.launch {
            while (isActive) {
                try {
                    // --- Update sensors health ---
                    sensorsHealthy = sensorChecks(1000L)

                    // --- Sync real aircraft state ---
                    syncState()

                    // --- Enforce consistency ---
                    enforceModeConsistency()

                    // --- Safety hooks ---
                    rcInput?.hasCenteredJoystick()?.let {
                        // stop everything to if any joystick is moved
                        manualControl()
                    }

                    if (!sensorsHealthy && state.isFlying()) {
                        logger.w { "Sensors lost during flight!" }
                        // you could trigger RTL or LAND here
                    }

                    if (!telemetry.isActive()) {
                        logger.w { "Unexpected telemetry stop!" }
                        // you could trigger RTL or LAND here
                    }

                } catch (e: Exception) {
                    logger.e { "Guard loop error: ${e.message}" }
                    // emergency land? manual control?
                }

                delay(200L) // tune this (100–500ms is reasonable)
            }
        }
    }

    fun registerHandlerScope(handlerScope: CoroutineScope) {
        telemetry.registerHandlerScope(handlerScope)
        registerMissionListeners(handlerScope)
        startCommandProcessor(handlerScope)
        startGuardLoop(handlerScope)
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
                    awaitFlightState(true)
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
                awaitFlightState(false)
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

        requestMode(ArduCopterFlightMode.STABILIZE)
            .onFailure {
                logger.w { "Manual control mode rejected: ${it.message}" }
            }
    }

    suspend fun unload() {
        // Stop ongoing processes
        commandJob?.cancel()
        commandJob = null
        guardJob?.cancel()
        guardJob = null
        // Reverse boot sequence if needed
        stopTelemetry(500L)
        isPowerOff = true
    }

    fun armMotors() {
        logger.d { "Arming motors" }
        FCManager.armMotors()
    }

    fun disarmMotors() {
        logger.d { "Disarming motors" }
        FCManager.disarmMotors()
    }

    suspend fun waitArmTransition(mustArm: Boolean, timeout: Long): Boolean {
        fun motorsMatchTarget(): Boolean = mustArm == state.isArmed()

        val motorsUpdated = AsyncUtils.waitTimeout(timeout = timeout, isReady = ::motorsMatchTarget)

        if (mustArm && state.isArmed()) {
            logger.i { "Aircraft armed" }
        }

        if (!mustArm && !state.isArmed()) {
            logger.i { "Aircraft in standby" }
        }

        return motorsUpdated
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

    fun takeOff() {
        logger.d { "Aircraft taking off" }
        applyState(TakeoffAircraftState)
        FCManager.startTakeoff()
    }

    fun land() {
        logger.d { "Aircraft landing" }
        applyState(LandAircraftState)

        controlTransition(ControlAuthority.TIMELINE_COMMAND)
        mission.doLand { error ->
            logger.i { "Landed:$error" }
            controlTransition(ControlAuthority.NONE)
        }
    }

    suspend fun awaitFlightState(takingOff: Boolean): Boolean {
        logger.d { "Waiting for ${if (takingOff) "taking off" else "touching ground"}" }
        fun flyingMatchesTarget(): Boolean = takingOff == state.isFlying()
        AsyncUtils.waitReady(100L, isReady = ::flyingMatchesTarget)

        if (takingOff && state.isFlying()) {
            logger.d { "Aircraft flying" }
        }

        if (!takingOff && !state.isFlying()) {
            logger.d { "Aircraft on the ground" }
        }

        return flyingMatchesTarget()
    }

    suspend fun waitAndConfirmLanding() {
        // https://developer.dji.com/api-reference/android-api/Components/FlightController/DJIFlightController.html#djiflightcontroller_confirmlanding_inline
        logger.d { "\tWaiting altitude of 0.3m" }
        AsyncUtils.waitReady(100L, FCManager::needLandingConfirmation)

        FCManager.confirmLanding {
            logger.d { "\tLanding confirm" }
        }
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

    fun doGoHome() {
        controlTransition(ControlAuthority.TIMELINE_COMMAND)
        mission.doGoHome { error ->
            logger.i { "RTL: $error" }
            controlTransition(ControlAuthority.NONE)
        }
    }
}
