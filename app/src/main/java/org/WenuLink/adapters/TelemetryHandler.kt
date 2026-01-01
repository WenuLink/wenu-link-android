package org.WenuLink.adapters

import org.WenuLink.sdk.AircraftManager
import org.WenuLink.sdk.FCManager
import org.WenuLink.sdk.RCManager
import org.WenuLink.sdk.SimManager
import io.getstream.log.taggedLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
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
    private var lastTelemetryData: TelemetryData? = null
    private var runSimulation: Boolean = false

    private val _isDataFlowing = MutableStateFlow(false)
    val isDataFlowing: StateFlow<Boolean> = _isDataFlowing.asStateFlow()

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

    fun isSimulationReady(): Boolean = SimManager.isAvailable()

    fun isSimulationActive(): Boolean = SimManager.isActive()

    @Synchronized
    fun enableSimulation(enable: Boolean) {
        logger.i { "Enable Simulation $enable" }

        if (!isSimulationReady()) {
            logger.e { "Unable to set, Simulation is not available." }
            return
        }

        if (runSimulation == enable) return

        runSimulation = enable
    }

    fun registerSimState(register: Boolean, handlerScope: CoroutineScope) {
        logger.d { "registerSimState $register" }
        // Always clear first
        SimManager.unregisterStateCallback()
        if (register) SimManager.registerStateCallback { state ->
            handlerScope.launch{ updateTelemetryData(SimManager.state2telemetry(state)) }
        }
    }

    fun registerRealState(register: Boolean, handlerScope: CoroutineScope) {
        if (!AircraftManager.isAircraftConnected()) {
            logger.w { "Aircraft not connected. Not ready for telemetry." }
            return
        }

        logger.d { "registerRealState $register" }
        // Always clear first
        FCManager.unregisterStateCallback()
        if (register) FCManager.registerStateCallback { state ->
            handlerScope.launch { updateTelemetryData(FCManager.state2telemetry(state)) }
        }
    }

    @Synchronized
    fun registerStateListeners(register: Boolean, handlerScope: CoroutineScope) {
        logger.d { "registerStateListeners: $register (runSimulation: $runSimulation)" }
        if (runSimulation) registerSimState(register, handlerScope)
        else registerRealState(register, handlerScope)
    }

    fun listenRemoteController(listen: Boolean, handlerScope: CoroutineScope) {
        handlerScope.launch {
            if (listen) RCManager.startListeners()
            else RCManager.stopListeners()
        }
    }

    fun listenAircraft(listen: Boolean, handlerScope: CoroutineScope) {
        handlerScope.launch {
            if (listen) AircraftManager.startListeners()
            else AircraftManager.stopListeners()
        }
    }

    fun listenSimulation(listen: Boolean, handlerScope: CoroutineScope) {
        if (listen) SimManager.run { error ->
            handlerScope.launch {
                if (error == null) logger.i { "Simulation is running" }
                else logger.e { "Unable to start simulation: $error" }
            }
        }
        else SimManager.stop { error ->
            handlerScope.launch {
                if (error == null) logger.i { "Simulation is stopped" }
                else logger.e { "Unable to stop simulation: $error" }
            }
        }
    }

    fun listenVehicleState(listen: Boolean, handlerScope: CoroutineScope) {
        if (runSimulation) listenSimulation(listen, handlerScope)
        else listenAircraft(listen, handlerScope)
    }

    fun startDataFlow(handlerScope: CoroutineScope) {
        logger.d { "startDataFlow()" }
        if (isFlowing()) {
            logger.w { "Telemetry already flowing!" }
            return
        }
        // Start processes
        logger.i { "Starting Telemetry." }
        _isListeningRC.value = true
        _isListeningAircraft.value = true
        // Set listeners
        registerStateListeners(true, handlerScope)
    }

    fun stopDataFlow(handlerScope: CoroutineScope) {
        logger.d { "stopDataFlow()" }
        if (!isFlowing()) {
            logger.d { "No telemetry data to stop!" }
            return
        }
        // Stop processes
        logger.i { "Stopping Telemetry" }
        _isListeningRC.value = false
        _isListeningAircraft.value = false
        // Clear listeners and data
        registerStateListeners(false, handlerScope)
        updateTelemetryData(null)
    }

    fun isActive(): Boolean = _isListeningRC.value && _isListeningAircraft.value && _isDataFlowing.value

    fun registerListenerScope(handlerScope: CoroutineScope) {
        isListeningRC.distinctUntilChangedBy { it }
            .onEach { listenRemoteController(it, handlerScope) }
            .launchIn(handlerScope)

        isListeningAircraft.distinctUntilChangedBy { it }
            .onEach { listenVehicleState(it, handlerScope) }
            .launchIn(handlerScope)

        isDataFlowing.distinctUntilChangedBy { it }
            .onEach {
                if (it) startDataFlow(handlerScope)
                else stopDataFlow(handlerScope)
                logger.d { "isDataFlowing: $it" }
            }
            .launchIn(handlerScope)
    }

    fun isFlowing(): Boolean {
        // RC should always exists
        var isFlowing = RCManager.isUpdated()
        // If simulation activated, must wait for its shutdown
        isFlowing = if (runSimulation) isFlowing || isSimulationActive()
        // Assumes that if not sim, Aircraft is present
        else isFlowing && AircraftManager.isUpdated()
        return isFlowing
    }

    fun start(start: Boolean, handlerScope: CoroutineScope, onResult: (String?) -> Unit) {
        logger.d { "start($start)" }
        if (start) {
            // Starts and wait for data
            _isDataFlowing.value = true
            Utils.waitReadiness(handlerScope, isReady = ::isFlowing) { ready ->
                if (ready)
                    Utils.waitReadiness(handlerScope, isReady = ::hasTelemetryData) { ready ->
                        // check for data
                        if (ready)
                            onResult(null) // Indicate success
                        else
                            onResult("No telemetry data received!") // Indicate failure
                    }
                else onResult("Telemetry cannot start!") // Indicate failure
            }
        } else {
            _isDataFlowing.value = false
            Utils.waitReadiness(
                handlerScope,
                invertCondition = true,
                isReady = ::isFlowing
            ) { ready ->
                // check for data
                if (ready)
                    onResult(null) // Indicate success
                else
                    onResult("Unable to stop telemetry!") // Indicate failure
            }
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

        logger.d { "Home position not set, requesting update." }
        FCManager.setHomePosition { error ->
            if (error != null) logger.w { "Unable to set home position: $error" }
        }
        return null
    }
}
