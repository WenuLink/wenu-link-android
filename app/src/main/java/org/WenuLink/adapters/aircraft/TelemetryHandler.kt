package org.WenuLink.adapters.aircraft

import io.getstream.log.taggedLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.WenuLink.adapters.AsyncUtils
import org.WenuLink.adapters.commands.IHandler
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
    private var lastIMUState: IMUState? = null
    val isSimulationAvailable: Boolean
        get() = SimManager.isAvailable()
    val isSimulationActive: Boolean
        get() = SimManager.isActive()
    private var mustRunSimulation: Boolean = false

    private val _isBroadcasting = MutableStateFlow(false)
    val isBroadcasting: StateFlow<Boolean> = _isBroadcasting.asStateFlow()

    @Synchronized
    fun hasData(): Boolean = lastTelemetryData != null

    @Synchronized
    fun getData(): TelemetryData? = lastTelemetryData

    @Synchronized
    private fun updateTelemetryData(telemetry: TelemetryData?) {
        lastTelemetryData = telemetry
    }

    @Synchronized
    fun enableSimulation(enable: Boolean) {
        if (isActive()) {
            logger.e { "Unable to set runSimulation=$enable, Telemetry active." }
            return
        }

        if (!isSimulationAvailable) {
            logger.e { "Unable to set runSimulation=$enable, Simulation not available." }
            return
        }

        mustRunSimulation = enable
        logger.i { "Enable Simulation $mustRunSimulation" }
    }

    fun registerSimState(register: Boolean) {
        // Always clear first
        SimManager.unregisterStateCallback()
        if (register) {
            SimManager.registerStateCallback { state ->
                updateTelemetryData(SimManager.state2telemetry(state, lastTelemetryData))
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
                updateTelemetryData(FCManager.state2telemetry(state))
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
        }
        // Sensor listeners
        registerSensorState(register)
    }

    fun listenRemoteController(listen: Boolean) {
        if (listen) {
            RCManager.startListeners()
        } else {
            RCManager.stopListeners()
        }
    }

    fun listenAircraft(listen: Boolean) {
        if (listen) {
            AircraftManager.startListeners()
        } else {
            AircraftManager.stopListeners()
        }
    }

    fun listenSimulation(listen: Boolean) {
        if (listen) {
            SimManager.run { error ->
                if (error == null) {
                    logger.i { "Simulation running." }
                } else {
                    logger.e { "Unable to start simulation: $error" }
                }
            }
        } else {
            SimManager.stop { error ->
                if (error == null) {
                    logger.i { "Simulation stopped." }
                } else {
                    logger.e { "Unable to stop simulation: $error" }
                }
            }
        }
    }

    fun listenVehicleState(listen: Boolean) {
        if (mustRunSimulation) {
            listenSimulation(listen)
        } else {
            listenAircraft(listen)
        }
    }

    fun registerSensorState(listen: Boolean) {
        FCManager.unregisterIMUState() // always clear first
        if (listen) {
            FCManager.registerIMUState { imuState ->
                updateIMUState(imuState)
            }
        } else {
            updateIMUState(null)
        }
    }

    @Synchronized
    fun isReadingSensors() = lastIMUState != null

    @Synchronized
    fun updateIMUState(imuState: IMUState?) {
        lastIMUState = imuState
    }

    @Synchronized
    fun isCompassOk() = FCManager.compassOk()

    @Synchronized
    fun isAccelerometerOk() = lastIMUState?.accelerometer?.all { it == SensorState.OK } ?: false

    @Synchronized
    fun isGyroscopeOk() = lastIMUState?.gyroscope?.all { it == SensorState.OK } ?: false

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
        stopBroadcast()
    }

    fun isReadingData(): Boolean {
        // RC should always exists
        var isFlowing = RCManager.isUpdated()
        // If simulation activated, must wait for its startup
        isFlowing = isFlowing &&
            if (mustRunSimulation) {
                isSimulationActive
            } // Assumes that if not sim, Aircraft is present
            else {
                AircraftManager.isUpdated()
            }
        return isFlowing
    }

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
        AsyncUtils.waitReady(delay, isReady = ::stopReading)
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

    fun getAircraftBattery(): BatteryData {
        // TODO: Maybe include some custom battery level
        return if (mustRunSimulation) {
            RCManager.getBatteryData()
        } else {
            AircraftManager.getBatteryData()
        }
    }

    fun getAirlinkSignal(): IntArray = if (mustRunSimulation) {
        intArrayOf(98, 95)
    } else {
        AircraftManager.getAirlinkData()
    }

    fun hasActiveJoystickInput(): Boolean = getRCData()?.hasCenteredJoystick() == false
}
