package org.WenuLink.adapters.aircraft

import io.getstream.log.taggedLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.WenuLink.adapters.AsyncUtils
import org.WenuLink.commands.IHandler
import org.WenuLink.sdk.AircraftManager
import org.WenuLink.sdk.FCManager
import org.WenuLink.sdk.RCManager
import org.WenuLink.sdk.SimManager

class TelemetryHandler : IHandler<TelemetryHandler> {
    companion object {
        private var mInstance: TelemetryHandler? = null

        @Synchronized
        fun getInstance(): TelemetryHandler {
            if (mInstance == null) {
                mInstance = TelemetryHandler()
            }
            return mInstance!!
        }
    }

    private val logger by taggedLogger(TelemetryHandler::class.java.simpleName)

    private val _isListeningRC = MutableStateFlow(false)
    val isListeningRC: StateFlow<Boolean> = _isListeningRC.asStateFlow()
    private val _isListeningAircraft = MutableStateFlow(false)
    val isListeningAircraft: StateFlow<Boolean> = _isListeningAircraft.asStateFlow()
    private var lastTelemetryData: TelemetryData? = null
    private var lastIMUState: IMUState = IMUState()
    val isSimulationAvailable: Boolean
        get() = SimManager.isAvailable()
    val isSimulationActive: Boolean
        get() = SimManager.isActive()
    private var mustRunSimulation = false

    private val _isBroadcasting = MutableStateFlow(false)
    val isBroadcasting: StateFlow<Boolean> = _isBroadcasting.asStateFlow()

    @Synchronized
    fun hasData() = lastTelemetryData != null

    @Synchronized
    fun getData(): TelemetryData? = lastTelemetryData

    @Synchronized
    private fun updateTelemetryData(telemetry: TelemetryData?) {
        lastTelemetryData = telemetry
    }

    @Synchronized
    fun enableSimulation(enable: Boolean) = if (isActive()) {
        // TODO: move to CommandResult
        logger.e { "Unable to set runSimulation=$enable, Telemetry active." }
    } else if (!isSimulationAvailable) {
        logger.e { "Unable to set runSimulation=$enable, Simulation not available." }
    } else {
        if (mustRunSimulation && !enable) {
            logger.w { "Deactivate simulation to reboot the Aircraft for real aircraft connection" }
        }

        mustRunSimulation = enable
        logger.i { "Enable Simulation $mustRunSimulation" }
    }

    fun registerSimState(register: Boolean) {
        // Always clear first
        SimManager.unregisterStateCallback()
        if (register) {
            SimManager.registerStateCallback { state ->
                updateTelemetryData(SimManager.state2Telemetry(state, lastTelemetryData))
            }
        }
    }

    fun registerRealState(register: Boolean) {
        if (!AircraftManager.isAircraftConnected()) {
            logger.w { "Aircraft not connected. Not ready for telemetry." }
            return
        }

        // Always clear first
        FCManager.unregisterStateCallback()
        if (register) {
            FCManager.registerStateCallback { state ->
                // TODO: positionX,Y,Z values must be updated
                updateTelemetryData(FCManager.state2Telemetry(state))
            }
        }
    }

    @Synchronized
    fun registerStateListeners(register: Boolean) {
        logger.d { "registerStateListeners: $register (runSimulation: $mustRunSimulation)" }
        if (mustRunSimulation) {
            registerSimState(register)
        } else {
            registerRealState(register)
            // Sensor listeners only for real aircraft
            registerIMUListener(register)
        }
    }

    fun listenRemoteController(listen: Boolean) = if (listen) {
        RCManager.startListeners()
    } else {
        RCManager.stopListeners()
    }

    fun listenAircraft(listen: Boolean) = if (listen) {
        AircraftManager.startListeners()
    } else {
        AircraftManager.stopListeners()
    }

    suspend fun listenSimulation(listen: Boolean) {
        var attempts = 0
        if (listen) {
            while (attempts < 3) {
                val runError = SimManager.run() ?: break
                logger.w { "Error in run simulation: $runError. Trying again" }
                attempts++
                delay(500L)
            }
            if (attempts < 3) {
                logger.i { "Simulation running." }
            } else {
                logger.e { "Unable to start simulation, 3 failed attempts. Stoping" }
            }
        } else {
            val stopError = SimManager.stop()
            if (stopError == null) {
                logger.i { "Simulation stopped." }
            } else {
                logger.e { "Unable to stop simulation: $stopError" }
            }
        }
    }

    suspend fun listenVehicleState(listen: Boolean) = if (mustRunSimulation) {
        listenSimulation(listen)
    } else {
        listenAircraft(listen)
    }

    @Synchronized
    fun updateIMUState(imuState: IMUState) {
        lastIMUState = lastIMUState.copy(
            gyroscope = imuState.gyroscope,
            accelerometer = imuState.accelerometer
        )
    }

    fun registerIMUListener(listen: Boolean) {
        // These sensors are only reported with real Aircraft
        if (listen) {
            val nSensors = FCManager.getIMUCount()

            if (nSensors <= 0) {
                logger.i { "No IMU sensor found!" }
                return
            }

            logger.d { "Listening $nSensors IMU(s)" }

            val state = IMUState(
                MutableList(nSensors) { SensorState.BOOT },
                MutableList(nSensors) { SensorState.BOOT }
            )

            FCManager.registerIMUStateCallback { imuState ->
                // logger.i { "setIMUStateCallback: ${imuState.index}" }
                // The callback is executed one time per sensor, with -1 indicating the list's end
                if (imuState.index == -1) {
                    // Publish a copy after receiving the entire list
                    updateIMUState(state)
                    return@registerIMUStateCallback
                }
                // assumes same number gyros and accel
                state.gyroscope[imuState.index] = FCManager.sensorState(imuState.gyroscopeState)
                state.accelerometer[imuState.index] =
                    FCManager.sensorState(imuState.accelerometerState)
            }
        } else {
            logger.d { "Stop listening IMU(s)" }
            FCManager.unregisterIMUStateCallback()
            updateIMUState(IMUState())
        }
    }

    private fun noIMU() = FCManager.getIMUCount() == 0

    @Synchronized
    fun isReadingSensors() = noIMU() || lastIMUState.gyroscope.isNotEmpty() || mustRunSimulation

    @Synchronized
    fun isCompassOk() = noIMU() || FCManager.compassOk() || mustRunSimulation

    @Synchronized
    fun isAccelerometerOk() = noIMU() || mustRunSimulation ||
        lastIMUState.accelerometer.all { it == SensorState.OK }

    @Synchronized
    fun isGyroscopeOk() = noIMU() || mustRunSimulation ||
        lastIMUState.gyroscope.all { it == SensorState.OK }

    fun startBroadcast() {
        if (isReadingData()) {
            logger.d { "Telemetry already flowing!" }
            return
        }
        // Start processes
        _isListeningRC.value = true
        _isListeningAircraft.value = true
        // Set listeners
        registerStateListeners(true)
    }

    fun stopBroadcast() {
        if (!isReadingData()) {
            logger.d { "No telemetry data to stop!" }
            return
        }
        // Stop processes
        _isListeningRC.value = false
        _isListeningAircraft.value = false
        // Clear listeners and data
        registerStateListeners(false)
        updateTelemetryData(null)
    }

    fun isActive(): Boolean = _isListeningRC.value &&
        _isListeningAircraft.value &&
        _isBroadcasting.value &&
        isReadingData() &&
        hasData()

    override fun registerScope(scope: CoroutineScope) {
        isListeningRC.distinctUntilChangedBy { it }
            .onEach { listenRemoteController(it) }
            .launchIn(scope)

        isListeningAircraft.distinctUntilChangedBy { it }
            .onEach { listenVehicleState(it) }
            .launchIn(scope)

        isBroadcasting.distinctUntilChangedBy { it }
            .onEach {
                logger.d { "Requesting to ${if (it) "start" else "stop"} telemetry broadcast." }
                if (it) {
                    startBroadcast()
                } else {
                    stopBroadcast()
                }
            }
            .launchIn(scope)
    }

    override fun unload() {
        launchTelemetry(false)
    }

    private fun hasAircraftData(): Boolean = // If simulation activated, must wait for its startup
        if (mustRunSimulation) {
            isSimulationActive
        } // Assumes that if not sim, Aircraft is present
        else {
            AircraftManager.isUpdated()
        }

    fun isReadingData(): Boolean = RCManager.isUpdated() && hasAircraftData() && isReadingSensors()

    suspend fun waitDataReading(timeout: Long = 5000L): Boolean {
        // First check wait for listeners transfer data
        var listenersOk = AsyncUtils.waitTimeout(timeout = timeout, isReady = ::isReadingData)
        if (!listenersOk) return false

        // Second wait to receive the data ready for broadcast
        listenersOk = AsyncUtils.waitTimeout(timeout = timeout, isReady = ::hasData)
        logger.d { "Telemetry broadcasting data: $listenersOk" }
        return listenersOk
    }

    suspend fun waitDataRemoving(delay: Long = 1000L): Boolean {
        // Wait for listeners to stop receiving data
        fun stopReading() = !isReadingData()
        AsyncUtils.waitReady(delay, ::stopReading)
        // reset the telemetry info
        updateTelemetryData(null)
        val listenersOk = !(isReadingData() || hasData())
        logger.d { "Telemetry stop: $listenersOk" }
        return listenersOk
    }

    fun launchTelemetry(start: Boolean) {
        _isBroadcasting.value = start
    }

    fun getRCData(): RCData? = RCManager.getHardwareData()

    fun getAircraftBattery(): BatteryData = // TODO: Maybe include some custom battery level
        if (mustRunSimulation) {
            RCManager.getBatteryData()
        } else {
            AircraftManager.getBatteryData()
        }

    fun getAirlinkSignal(): SignalQuality = if (mustRunSimulation) {
        SignalQuality(98, 95)
    } else {
        AircraftManager.getAirlinkData()
    }

    fun getAircraftModelName(): String = AircraftManager.getModelName()
}
