package org.WenuLink.adapters.aircraft

import io.getstream.log.taggedLogger
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineScope
import org.WenuLink.adapters.AsyncUtils
import org.WenuLink.commands.CommandHandler
import org.WenuLink.parameters.ArduPilotParametersProvider
import org.WenuLink.parameters.DJIParametersProvider
import org.WenuLink.parameters.ParameterRegistry
import org.WenuLink.sdk.FCManager

class AircraftHandler : CommandHandler<AircraftHandler>() {
    companion object {
        private var mInstance: AircraftHandler? = null

        fun getInstance(): AircraftHandler {
            if (mInstance == null) {
                mInstance = AircraftHandler()
            }

            return mInstance!!
        }
    }

    private val logger by taggedLogger(AircraftHandler::class.java.simpleName)
    val stateMachine = AircraftStateMachine()
    val state: AircraftState get() = stateMachine.state
    var sensorsTimestamp = System.currentTimeMillis()
    var homeTimestamp = sensorsTimestamp
    var sensorsHealthy = false
        private set
    val telemetry = TelemetryHandler.getInstance()
    val currentCoordinates: Coordinates3D?
        get() = telemetry.getData()?.let {
            Coordinates3D(it.latitude, it.longitude, it.relativeAltitude)
        }
    var isPowerOff = true
    val parameters by lazy {
        ParameterRegistry(
            listOf(
                ArduPilotParametersProvider,
                DJIParametersProvider(
                    FCManager.mInstance ?: error("FlightController not available")
                )
            )
        )
    }

    fun requestMode(mode: ArduCopterFlightMode): Result<Unit> {
        if (mode == state.flightMode) return Result.success(Unit)

        if (!stateMachine.isModeAllowed(mode)) {
            return Result.failure(
                IllegalStateException("Mode $mode not allowed from ${state.flightMode}")
            )
        }

        logger.d { "Mode change: ${state.flightMode} -> $mode" }

        stateMachine.updateFlightMode(mode)

        return Result.success(Unit)
    }

    private fun enforceModeConsistency() {
        if (!stateMachine.isModeAllowed(state.flightMode)) {
            stateMachine.syncArmState()
            return
        }

        val fallbackMode = if (state.isFlying()) {
            ArduCopterFlightMode.GUIDED
        } else {
            ArduCopterFlightMode.STABILIZE
        }

        logger.w {
            "Mode ${state.flightMode} invalid for state ${state.mavlink}, fallback to $fallbackMode"
        }

        stateMachine.updateFlightMode(fallbackMode)
    }

    fun canDispatchTransition(transition: StateTransition) = stateMachine.canDispatch(transition)

    fun dispatchTransition(transition: StateTransition) = stateMachine.dispatch(transition)

    private fun syncHomePosition() {
        if (state.isHomeSet()) return

        // Check for home position
        updateHomeCoordinatesFromAircraft()

        FCManager.getHomePosition()?.let {
            stateMachine.updateHomePosition(it)
            logger.i { "Home Location acquired: $it" }
        }
    }

    suspend fun syncState(sensorsInterval: Long = 1000L, homeInterval: Long = 5000L) {
        if (isPowerOff) return
        val currentTimestamp = System.currentTimeMillis()

        // only allow requests after homeInterval ms
        if ((currentTimestamp - homeTimestamp) >= homeInterval) {
            syncHomePosition()
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

    private suspend fun loadParameters(timeout: Long = 5000L): Boolean {
        logger.d { "Loading parameters" }
        parameters.load()
        return AsyncUtils.waitTimeout(timeout, 1000L, parameters::isLoaded)
    }

    private suspend fun sensorChecks(timeout: Long = 10000L): Boolean {
        val perSensorTime = (timeout / 3f).roundToLong()

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
    private fun updateHomeCoordinatesFromAircraft(): Boolean {
        if (FCManager.getHomePosition() != null) return true
        // Ask for home position
        logger.d { "Requesting home coordinates update with current aircraft's location." }
        FCManager.setHomePosition { error ->
            if (error != null) {
                logger.w { "Error request: $error" }
            }
        }
        return FCManager.getHomePosition() != null
    }

    suspend fun waitHomeSet(timeout: Long = 10000L): Boolean = AsyncUtils.waitTimeout(
        100L,
        timeout,
        state::isHomeSet
    )

    private suspend fun startTelemetry(timeout: Long = 5000L): Boolean {
        // Start telemetry process
        telemetry.launchTelemetry(true)
        // Second wait to receive the data ready for broadcast
        return telemetry.waitDataReading(timeout)
    }

    private suspend fun stopTelemetry(delay: Long = 1000L): Boolean {
        // Stop telemetry process
        telemetry.launchTelemetry(false)
        // Second wait to remove the existing data
        return telemetry.waitDataRemoving(delay)
    }

    suspend fun boot(timeout: Long = 5000L): String? {
        sensorsHealthy = false
        logger.d { "Aircraft booting..." }

        if (!loadParameters(timeout)) return "No parameters"
        logger.d { "\tParameters: OK" }

        if (!startTelemetry(timeout)) return "No telemetry"
        logger.d { "\tTelemetry: OK" }

        sensorsHealthy = sensorChecks(timeout)
        logger.d { "\tSensors healthy?: $sensorsHealthy" }

        logger.d { "Aircraft boot: OK" }
        isPowerOff = false
        return null
    }

    override fun registerScope(scope: CoroutineScope) {
        telemetry.registerScope(scope)
        startCommandProcessor(scope, this@AircraftHandler, logger)
    }

    override fun unload() {
        telemetry.unload()
        super.unload()
    }

    suspend fun shutdown(): String? {
        sensorsHealthy = false
        // Reverse boot sequence
        stopTelemetry(500L)
        isPowerOff = true
        return null
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
        val motorsUpdated = AsyncUtils.waitTimeout(timeout = timeout) { mustArm == state.isArmed() }

        if (motorsUpdated) {
            logger.i { if (mustArm) "Aircraft armed" else "Aircraft in standby" }
        } else {
            logger.w { "Timeout: ${if (mustArm) "armed" else "disarmed"} state not reached" }
        }

        return motorsUpdated
    }

    fun takeOff() {
        logger.d { "Aircraft taking off" }
        FCManager.startTakeoff()
    }

    suspend fun waitFlightState(takingOff: Boolean, timeout: Long): Boolean {
        logger.d { "Waiting for ${if (takingOff) "taking off" else "touching ground"}" }

        val flyingStateUpdated = AsyncUtils.waitTimeout(100L, timeout) {
            takingOff == state.isFlying()
        }

        if (flyingStateUpdated) {
            logger.i { if (takingOff) "Aircraft flying" else "Aircraft on the ground" }
        } else {
            logger.w { "Timeout: ${if (takingOff) "takeoff" else "landing"} state not reached" }
        }

        return flyingStateUpdated
    }

    suspend fun waitAndConfirmLanding() {
        // https://developer.dji.com/api-reference/android-api/Components/FlightController/DJIFlightController.html#djiflightcontroller_confirmlanding_inline
        logger.d { "\tWaiting altitude of 0.3m" }
        AsyncUtils.waitReady(100L) { FCManager.getAltitude() < 0.5f }
        val confirmationNeeded = AsyncUtils.waitTimeout(
            100L,
            5000L,
            FCManager::needLandingConfirmation
        )

        if (confirmationNeeded) {
            FCManager.confirmLanding { logger.d { "\tLanding confirmed" } }
        } else {
            logger.w { "Landing confirmation timeout" }
        }
    }
}
