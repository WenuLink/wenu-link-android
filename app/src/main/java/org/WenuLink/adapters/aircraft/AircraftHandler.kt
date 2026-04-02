package org.WenuLink.adapters.aircraft

import com.MAVLink.enums.MAV_MODE_FLAG
import io.getstream.log.taggedLogger
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.WenuLink.adapters.AsyncUtils
import org.WenuLink.adapters.MissionHandler
import org.WenuLink.adapters.camera.CameraHandler
import org.WenuLink.adapters.commands.CommandProcessor
import org.WenuLink.adapters.commands.ICommand
import org.WenuLink.adapters.commands.IHandler
import org.WenuLink.sdk.FCManager
import org.WenuLink.sdk.MissionActionManager
import org.WenuLink.sdk.MissionManager

class AircraftHandler : IHandler<AircraftHandler> {
    companion object {
        private var mInstance: AircraftHandler? = null

        fun getInstance(serviceScope: CoroutineScope? = null): AircraftHandler {
            if (mInstance == null) {
                mInstance = AircraftHandler()
            }
            if (serviceScope != null) {
                mInstance!!.registerScope(serviceScope)
            }

            return mInstance!!
        }
    }

    private val logger by taggedLogger(AircraftHandler::class.java.simpleName)
    val startTimestamp = System.currentTimeMillis()
    val systemBootTime
        get() = System.currentTimeMillis() - startTimestamp
    var baseMode = MAV_MODE_FLAG.MAV_MODE_FLAG_CUSTOM_MODE_ENABLED
        private set
    var copterFlightMode = ArduCopterFlightMode.STABILIZE
        private set
    val stateMachine = AircraftStateMachine()
    val state: AircraftState get() = stateMachine.state
    var sensorsTimestamp = startTimestamp
    var homeTimestamp = startTimestamp
    var sensorsHealthy = false
        private set
    val mission = MissionHandler.getInstance()
    val telemetry = TelemetryHandler.getInstance()
    val cameras = CameraHandler.getInstance()
    var hasCameras = false
    var isPowerOff = true
    private val processor = CommandProcessor(this, logger)
    private var monitorJob: Job? = null

    override fun dispatchCommand(cmd: ICommand<AircraftHandler>, onResult: (String?) -> Unit) =
        processor.dispatch(cmd, onResult)

    override fun stopCommand(): String? = processor.cancel()

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
        ArduCopterFlightMode.GUIDED -> state.isFlying() || state.isStandBy()
        ArduCopterFlightMode.AUTO -> state.isFlying() && mission.isMissionRunning
        ArduCopterFlightMode.LAND, ArduCopterFlightMode.RTL -> state.isFlying()
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
        if (isModeAllowed(copterFlightMode)) {
            syncBaseMode()
            return
        }

        val fallbackMode = if (state.isFlying()) {
            ArduCopterFlightMode.GUIDED
        } else {
            ArduCopterFlightMode.STABILIZE
        }

        logger.w {
            "Mode $copterFlightMode invalid for state ${state.mavlink}, fallback to $fallbackMode"
        }

        setMode(fallbackMode)
    }

    suspend fun syncState(sensorsInterval: Long = 1000L, homeInterval: Long = 5000L) {
        if (isPowerOff) return
        val currentTimestamp = System.currentTimeMillis()

        // Check for home position
        val homePos = FCManager.getHomePosition()
        // only allow requests after homeInterval ms
        if ((currentTimestamp - homeTimestamp) >= homeInterval) {
            if (homePos == null) {
                val homeSet = waitHomeSet(100L)
                if (!homeSet) logger.e { "Home Location still not set!" }
            } else {
                stateMachine.updateHomePosition(homePos)
            }
            homeTimestamp = currentTimestamp
        }

        // only allows check sensors after sensorsInterval ms
        if ((currentTimestamp - sensorsTimestamp) >= sensorsInterval) {
            sensorsHealthy = sensorChecks(100L) && state.isHomeSet()
            sensorsTimestamp = currentTimestamp
        }

        // Check for arm and flying conditions always
        val fcState = state.resolveFrom(
            FCManager.areMotorsArmed(),
            FCManager.isFlying()
        )

        // Force new logic state update only when different
        if (!stateMachine.hasStateChanged(fcState)) return

        logger.w { "State reconciliation: $state -> $fcState" }

        stateMachine.forceSet(fcState)
        enforceModeConsistency()
    }

    private fun safetyChecks() {
        // --- Safety hooks ---
        if (telemetry.hasActiveJoystickInput()) {
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
    }

    fun processUserPreferences(): Boolean {
        // TODO: process user preferences (flight speed, RTL altitude, take off altitude)
        return true
    }

    suspend fun sensorChecks(timeout: Long = 10000L): Boolean {
        val perSensorTime = (timeout.toFloat() / 3).roundToLong()
        logger.i { "Waiting sensors data" }

        if (!AsyncUtils.waitTimeout(100L, timeout, telemetry::isReadingSensors)) {
            logger.e { "No sensor readings!" }
            return false
        }

        // https://developer.dji.com/api-reference/android-api/Components/Compass/DJICompass.html
        val compassOk = AsyncUtils.waitTimeout(
            100L,
            perSensorTime,
            telemetry::isCompassOk
        )
        if (!compassOk) logger.e { "Compass error!" }

        // https://developer.dji.com/api-reference/android-api/Components/IMUState/DJIIMUState.html
        val accOk = AsyncUtils.waitTimeout(
            100L,
            perSensorTime,
            telemetry::isAccelerometerOk
        )
        if (!accOk) logger.e { "Accelerometer error!" }

        val gyroOk = AsyncUtils.waitTimeout(
            100L,
            perSensorTime,
            telemetry::isGyroscopeOk
        )
        if (!gyroOk) logger.e { "Gyroscope error!" }

        return compassOk && accOk && gyroOk
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
        return telemetry.waitDataReading(timeout)
    }

    suspend fun stopTelemetry(delay: Long = 1000L): Boolean {
        // Stop telemetry process
        telemetry.launchTelemetry(false)
        // Second wait to remove the existing data
        return telemetry.waitDataRemoving(delay)
    }

    suspend fun boot(timeout: Long = 5000L): String? {
        sensorsHealthy = false
        logger.d { "Aircraft booting..." }

        if (!processUserPreferences()) return "No user preferences"
        logger.d { "\tLoading preferences: TBD" }

        if (!startTelemetry(timeout)) return "No telemetry"
        logger.d { "\tInit. telemetry: OK" }

        sensorsHealthy = sensorChecks(timeout)
        hasCameras = cameras.initCameras()
        logger.d { "\tSensors healthy?: $sensorsHealthy" }

        // initial standby state is async, until then, manual control
        manualControl()

        logger.d { "Aircraft boot: OK" }
        isPowerOff = false
        return null
    }

    private fun startMonitorJob(scope: CoroutineScope) {
        monitorJob?.cancel()

        monitorJob = scope.launch {
            while (isActive) {
                try {
                    // --- Sync real aircraft state ---
                    syncState()

                    safetyChecks()

                    // TODO: mission.updateState() -> syncMission()
                } catch (e: Exception) {
                    logger.e { "Guard loop error: ${e.message}" }
                    // emergency land? manual control?
                }

                delay(200L) // tune this (100–500ms is reasonable)
            }
        }
    }

    override fun registerScope(scope: CoroutineScope) {
        telemetry.registerHandlerScope(scope)
        cameras.registerHandlerScope(scope)
        registerMissionListeners(scope)
        startMonitorJob(scope)
        processor.start(scope)
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

                    if (index == 0 && stateMachine.isMissionWaypoint()) {
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
        if (stateMachine.isMissionWaypoint()) mission.stopWaypoint()
        if (stateMachine.isTimelineCommand()) mission.cancelCommand()
    }

    fun controlTransition(authority: ControlAuthority) {
        // Decide policy: reject or stop mission
        if (!stateMachine.isNewControlAuthority(authority)) return
        // Always stop everything
        cancelMission()
        stopCommand()
        logger.d { "Control transition: ${state.controlAuthority}->$authority" }
        stateMachine.setControlAuthority(authority)
    }

    fun manualControl() {
        controlTransition(ControlAuthority.REMOTE_CONTROLLER)

        requestMode(ArduCopterFlightMode.STABILIZE)
            .onFailure {
                logger.w { "Manual control mode rejected: ${it.message}" }
            }
    }

    suspend fun unload() {
        // Stop monitor
        monitorJob?.cancel()
        monitorJob = null
        sensorsHealthy = false
        // Reverse boot sequence
        stopTelemetry(500L)
        processor.stop()
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

        if (motorsUpdated) {
            logger.i { "Aircraft armed" }
        } else {
            logger.i { "Aircraft in standby" }
        }

        return motorsUpdated
    }

    fun getCurrentCoordinates(): Coordinates3D? {
        logger.d { "getCurrentCoordinates" }

        val location = FCManager.fcInstance?.state?.aircraftLocation ?: return null
        val takeoffAltitude = FCManager.fcInstance?.state?.takeoffLocationAltitude

        logger.d {
            "getCurrentCoordinates: currentAltitude: ${location.altitude}, " +
                "takeoffAltitude: $takeoffAltitude"
        }

        return Coordinates3D(location.longitude, location.latitude, location.altitude)
    }

    fun takeOff() {
        logger.d { "Aircraft taking off" }
        FCManager.startTakeoff()
    }

    fun land() {
        logger.d { "Aircraft landing" }

        controlTransition(ControlAuthority.TIMELINE_COMMAND)
        mission.doLand { error ->
            logger.i { "Landed:$error" }
            controlTransition(ControlAuthority.NONE)
        }
    }

    suspend fun awaitFlightState(takingOff: Boolean): Boolean {
        logger.d { "Waiting for ${if (takingOff) "taking off" else "touching ground"}" }
        fun flyingMatchesTarget(): Boolean = takingOff == state.isFlying()
        AsyncUtils.waitReady(100L, ::flyingMatchesTarget)

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
