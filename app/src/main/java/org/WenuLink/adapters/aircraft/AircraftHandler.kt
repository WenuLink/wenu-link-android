package org.WenuLink.adapters.aircraft

import io.getstream.log.taggedLogger
import kotlinx.coroutines.CoroutineScope
import org.WenuLink.adapters.AsyncUtils
import org.WenuLink.commands.CommandHandler
import org.WenuLink.commands.CommandResult
import org.WenuLink.commands.UnitResult
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
    var sensorsHealthy = false
        private set
    val telemetry = TelemetryHandler.getInstance()
    val currentTelemetry: TelemetryData? get() = telemetry.getData()
    val globalCoordinates: Coordinates3D?
        get() {
            val data = currentTelemetry ?: return null
            val lat = data.latitude ?: return null
            val lon = data.longitude ?: return null
            val rel = data.altitude ?: return null
            logger.i { "globalCoordinates($lat, $lon, $rel)" }
            return Coordinates3D(lat, lon, rel)
        }
    val localPosition: Coordinates3D?
        get() {
            val data = currentTelemetry ?: return null
            val posX = data.positionX.toDouble()
            val posY = data.positionY.toDouble()
            val posZ = data.positionZ
            logger.i { "localCoordinates($posX, $posY, $posZ)" }
            return Coordinates3D(posX, posY, posZ)
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

    fun requestMode(mode: ArduCopterFlightMode): UnitResult {
        if (mode == state.flightMode) return CommandResult.ok

        val isAllowed = stateMachine.isModeAllowed(mode)

        if (isAllowed.hasError) {
            return CommandResult.error("Mode $mode not allowed: ${isAllowed.errorReason}")
        }

        logger.d { "Mode change: ${state.flightMode} -> $mode" }

        stateMachine.updateFlightMode(mode)

        return CommandResult.ok
    }

    fun syncSensors(sensorsInterval: Long = 1000L) {
        if (isPowerOff) return
        val currentTimestamp = System.currentTimeMillis()

        // only allows check sensors after sensorsInterval ms
        if ((currentTimestamp - sensorsTimestamp) >= sensorsInterval) {
            sensorsHealthy = sensorChecks()
            sensorsTimestamp = currentTimestamp
        }
    }

    fun syncState() {
        stateMachine.sync(
            currentTelemetry?.motorsOn ?: false,
            currentTelemetry?.isFlying ?: false
        )
    }

    private suspend fun loadParameters(timeout: Long = 5000L): Boolean {
        logger.d { "Loading parameters" }
        parameters.load()
        return AsyncUtils.waitTimeout(timeout, 1000L, parameters::isLoaded)
    }

    private fun sensorChecks(): Boolean {
        if (!telemetry.isReadingSensors()) {
            logger.e { "No sensor readings!" }
            return false
        }

        // https://developer.dji.com/api-reference/android-api/Components/Compass/DJICompass.html
        val compassOk = telemetry.isCompassOk()
        if (!compassOk) logger.e { "Compass error!" }

        // https://developer.dji.com/api-reference/android-api/Components/IMUState/DJIIMUState.html
        val accOk = telemetry.isAccelerometerOk()
        if (!accOk) logger.e { "Accelerometer error!" }

        val gyroOk = telemetry.isGyroscopeOk()
        if (!gyroOk) logger.e { "Gyroscope error!" }

        return compassOk && accOk && gyroOk
    }

    // check for home location and set
    fun updateHomeCoordinatesFromAircraft(): Boolean = FCManager.getHomePosition()?.let {
        logger.d { "Home coordinates acquired: $it." }
        stateMachine.updateHomePosition(it)
        true
    } ?: run {
        // Ask for home position
        logger.d { "Requesting home coordinates update with current aircraft's location." }
        FCManager.setHomePosition { error ->
            if (error != null) {
                logger.w { "Error in set home position: $error" }
            }
        }
        false
    }

    suspend fun waitHomeSet(timeout: Long = 10_000L): Boolean = AsyncUtils.waitTimeout(
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

    suspend fun boot(timeout: Long = 5000L): UnitResult {
        sensorsHealthy = false
        logger.d { "Aircraft booting..." }

        if (!loadParameters(timeout)) return CommandResult.error("No parameters")
        logger.d { "\tParameters: OK" }

        if (!startTelemetry(timeout)) return CommandResult.error("No telemetry")
        logger.d { "\tTelemetry: OK" }

        sensorsHealthy =
            AsyncUtils.waitTimeout(500L, timeout) { sensorChecks() } &&
            updateHomeCoordinatesFromAircraft()
        logger.d { "\tSensors healthy?: $sensorsHealthy" }

        logger.d { "Aircraft boot: OK" }
        syncState()
        isPowerOff = false
        return CommandResult.ok
    }

    override fun registerScope(scope: CoroutineScope) {
        telemetry.registerScope(scope)
        startCommandProcessor(scope, this@AircraftHandler, logger)
    }

    override fun unload() {
        telemetry.unload()
        super.unload()
    }

    suspend fun shutdown() {
        sensorsHealthy = false
        // Reverse boot sequence
        stopTelemetry(500L)
        isPowerOff = true
    }

    fun armMotors() {
        logger.d { "Arming motors" }
        FCManager.armMotors { error ->
            if (error != null) {
                logger.w { "Arm error: $error" }
            }
        }
    }

    fun disarmMotors() {
        logger.d { "Disarming motors" }
        FCManager.disarmMotors { error ->
            if (error != null) {
                logger.w { "Disarm error: $error" }
            }
        }
    }

    suspend fun waitArmTransition(mustArm: Boolean, timeout: Long): Boolean {
        logger.d { "Waiting for ${if (mustArm) "arming" else "disarming"} motors" }
        val motorsUpdated = AsyncUtils.waitTimeout(timeout = timeout) {
            mustArm == currentTelemetry?.motorsOn
        }

        if (motorsUpdated) {
            logger.i { if (mustArm) "Aircraft armed" else "Aircraft in standby" }
        } else {
            logger.w { "Timeout: ${if (mustArm) "armed" else "disarmed"} state not reached" }
        }

        syncState()

        return motorsUpdated
    }

    fun takeOff() {
        // sync and check for already-airborne cases
        if (state.isFlying()) {
            logger.w { "Takeoff called but aircraft already airborne, skipping" }
            return
        }
        logger.d { "Aircraft taking off" }
        FCManager.startTakeoff { error ->
            if (error != null) logger.e { "Takeoff error: $error" }
        }
    }

    suspend fun waitFlightState(takingOff: Boolean, timeout: Long): Boolean {
        logger.d { "Waiting for ${if (takingOff) "taking off" else "touching ground"}" }

        val flyingStateUpdated = AsyncUtils.waitTimeout(100L, timeout) {
            takingOff == currentTelemetry?.isFlying
        }

        if (flyingStateUpdated) {
            logger.i { if (takingOff) "Aircraft flying" else "Aircraft on the ground" }
        } else {
            logger.w { "Timeout: ${if (takingOff) "takeoff" else "landing"} state not reached" }
        }

        syncState()

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
