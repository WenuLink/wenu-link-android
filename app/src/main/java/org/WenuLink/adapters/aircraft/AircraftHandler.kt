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
    var sensorsTimestamp: Long = System.currentTimeMillis()
    var homeTimestamp: Long = 0
    var sensorsHealthy = false
        private set
    val telemetry = TelemetryHandler.getInstance()
    var isPowerOff = true
    val parameters by lazy {
        ParameterRegistry(
            listOf(
                ArduPilotParametersProvider,
                DJIParametersProvider(
                    FCManager.fcInstance ?: error("FlightController not available")
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
            val fallbackMode = if (state.isFlying()) {
                ArduCopterFlightMode.GUIDED
            } else {
                ArduCopterFlightMode.STABILIZE
            }

            logger.w {
                "Mode ${state.flightMode} invalid for state ${state.mavlink}, fallback to $fallbackMode"
            }

            stateMachine.updateFlightMode(fallbackMode)
        } else {
            stateMachine.syncArmState()
        }
    }

    fun canDispatchTransition(transition: StateTransition) = stateMachine.canDispatch(transition)

    fun dispatchTransition(transition: StateTransition) = stateMachine.dispatch(transition)

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

    suspend fun loadParameters(): Boolean {
        logger.i { "Waiting for parameters" }
        parameters.load()
        AsyncUtils.waitReady(1000L, parameters::isLoaded)
        return parameters.isLoaded()
    }

    suspend fun sensorChecks(timeout: Long = 10000L): Boolean {
        val perSensorTime = (timeout.toFloat() / 3).roundToLong()
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

        if (!loadParameters()) return "No parameters"
        logger.d { "\tLoading parameters: OK" }

        if (!startTelemetry(timeout)) return "No telemetry"
        logger.d { "\tInit. telemetry: OK" }

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
        FCManager.startTakeoff()
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
}
