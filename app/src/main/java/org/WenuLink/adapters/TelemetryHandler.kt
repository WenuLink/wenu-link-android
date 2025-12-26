package org.WenuLink.adapters

import org.WenuLink.sdk.AircraftManager
import org.WenuLink.sdk.FCManager
import org.WenuLink.sdk.RCManager
import org.WenuLink.sdk.SimManager
import io.getstream.log.taggedLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlin.getValue

class TelemetryHandler {
    companion object {
        private var mInstance: TelemetryHandler? = null

        @Synchronized
        fun getInstance(): TelemetryHandler {
            if (mInstance == null)
                mInstance = TelemetryHandler()
            return mInstance!!
        }

    }

    private val logger by taggedLogger("TelemetryHandler")

    private val _isListeningRC = MutableStateFlow(false)
    val isListeningRC: StateFlow<Boolean> = _isListeningRC.asStateFlow()
    private val _isListeningAircraft = MutableStateFlow(false)
    val isListeningAircraft: StateFlow<Boolean> = _isListeningAircraft.asStateFlow()
    private lateinit var handlerScope: CoroutineScope //(Dispatchers.Main + Job())
    private var lastTelemetryData: TelemetryData? = null
    private var runSimulation: Boolean = false

    private val _isDataFlowing = MutableStateFlow(false)
    val isDataFlowing: StateFlow<Boolean> = _isDataFlowing.asStateFlow()

    fun checkReadiness(
        delayTime: Long = 100,
        maxTime: Long = 1000,
        isReady: () -> Boolean,
        onResult: (Boolean) -> Unit
    ) {
        handlerScope.launch {
            val startTime = System.currentTimeMillis()

            while (System.currentTimeMillis() - startTime < maxTime) {
                if (isReady()) {
                    onResult(true) // Indicate readiness
                    return@launch // Exit the coroutine
                }
                delay(delayTime) // Wait for the next check
            }
            // If the timeout is reached without readiness
            onResult(false) // Indicate not ready
        }
    }

    @Synchronized
    fun hasTelemetryData(): Boolean {
        return lastTelemetryData != null
    }

    @Synchronized
    fun getTelemetryData(): TelemetryData? {
        return lastTelemetryData
    }

    @Synchronized
    private fun updateTelemetryData(telemetry: TelemetryData?) {
        lastTelemetryData = telemetry
    }

    fun isSimulationReady(): Boolean {
        logger.i { "isSimulationReady: ${SimManager.isAvailable()}" }
        return SimManager.isAvailable()
    }

    fun isSimulationActive(): Boolean = SimManager.isActive()

    @Synchronized
    fun enableSimulation(enable: Boolean) {
        logger.i { "Enable Simulation $enable" }

        if (!isSimulationReady()) {
            logger.e { "Unable to set, Simulation is not available." }
            return
        }

        if (runSimulation == enable) return

        val wasRunning = isFlowing()
        if (wasRunning) registerStateListeners(false)

        runSimulation = enable

        if (wasRunning) registerStateListeners(true)
    }

    fun registerSimState(register: Boolean) {
        logger.w { "registerSimState $register" }
        // Always clear first
        SimManager.unregisterStateCallback()
        if (register) SimManager.registerStateCallback { state ->
            updateTelemetryData(SimManager.state2telemetry(state))
        }
    }

    fun registerRealState(register: Boolean) {
        if (!AircraftManager.isAircraftConnected()) {
            logger.w { "Aircraft not connected. Not ready for telemetry." }
            return
        }

        logger.w { "registerRealState $register" }
        // Always clear first
        FCManager.unregisterStateCallback()
        if (register) FCManager.registerStateCallback { state ->
            updateTelemetryData(FCManager.state2telemetry(state))
        }
    }

    fun registerStateListeners(register: Boolean) {
        logger.w { "registerStateListeners runSimulation: $runSimulation" }
        if (runSimulation) registerSimState(register)
        else registerRealState(register)
    }

    fun listenRemoteController(listen: Boolean) {
        if (listen) RCManager.startListeners()
        else RCManager.stopListeners()
    }

    fun listenAircraft(listen: Boolean) {
        if (listen) AircraftManager.startListeners()
        else AircraftManager.stopListeners()
    }

    fun listenSimulation(listen: Boolean) {
        if (listen) SimManager.run { error ->
            if (error == null) logger.i { "Simulation is running" }
            else logger.e { "Unable to start simulation $error" }
        }
        else SimManager.stop { error ->
            if (error == null) logger.i { "Simulation is stopped" }
            else logger.e { "Unable to stop simulation $error" }
        }
    }

    fun startDataFlow() {
        logger.w { "startDataFlow()" }
        if (isFlowing()) {
            logger.w { "Telemetry already flowing!" }
            return
        }
        // Start processes
        logger.i { "Starting Telemetry." }
        _isListeningRC.value = true
        _isListeningAircraft.value = true
        // Set listeners
        registerStateListeners(true)
    }

    fun stopDataFlow() {
        logger.w { "stopDataFlow()" }
        if (!isFlowing()) {
            logger.w { "No telemetry data to stop!" }
            return
        }
        // Stop processes
        logger.i { "Stopping Telemetry." }
        _isListeningRC.value = false
        _isListeningAircraft.value = false
        // Clear listeners and data
        registerStateListeners(false)
        updateTelemetryData(null)
    }

    fun isFlowing(): Boolean {
        return _isListeningRC.value && _isListeningAircraft.value
    }

    fun registerListenerScope(serviceScope: CoroutineScope) {
        handlerScope = serviceScope

        isListeningRC.distinctUntilChangedBy { it }
            .onEach { listenRemoteController(it) }
            .launchIn(handlerScope)

        isListeningAircraft.distinctUntilChangedBy { it }
            .onEach {
                if (runSimulation) listenSimulation(it)
                else listenAircraft(it)
            }
            .launchIn(handlerScope)

        isDataFlowing.distinctUntilChangedBy { it }
            .onEach {
                if (it) startDataFlow()
                else stopDataFlow()
                logger.d { "isDataFlowing: $it" }
            }
            .launchIn(handlerScope)
    }

    fun areListenersRunning(): Boolean {
        // must exist RC and Aircraft or Simulation
        return RCManager.isUpdated() &&
            if (!runSimulation) AircraftManager.isUpdated() else SimManager.isActive()
    }

    fun start(start: Boolean, onResult: (String?) -> Unit) {
        logger.d { "start($start)" }
        if (start) {
            // Starts and wait for data
            _isDataFlowing.value = true
//            handlerScope.launch {
            // check listeners first
            checkReadiness(delayTime = 150, isReady = ::areListenersRunning) { ready ->
                if (ready)
                    checkReadiness(isReady = ::hasTelemetryData) { ready ->
                        // check for data
                        if (ready)
                            onResult(null) // Indicate success
                        else
                            onResult("No telemetry data received!") // Indicate failure
                    }
                else onResult("Telemetry cannot start!") // Indicate failure
            }
//            }
        } else {
            _isDataFlowing.value = false
            // TODO: check for a successful stop
            onResult(null) // Indicate success
        }
    }

    fun getRCData(): RCData? = RCManager.getHardwareData()

    fun getAircraftBattery(): BatteryData {
        // TODO: Maybe include some custom battery level
        return if (runSimulation) RCManager.getBatteryData()
        else AircraftManager.getBatteryData()
    }

    fun getHomePosition(): Triple<Double, Double, Int>? {
        // check for home location and set
        val homeLocation = FCManager.getHomePosition()
        if (homeLocation != null) return homeLocation

        logger.i { "Home position not set, requesting update." }
        FCManager.setHomePosition { error ->
            if (error != null) logger.w { "Unable to set home position: $error" }
        }
        return null
    }
}
