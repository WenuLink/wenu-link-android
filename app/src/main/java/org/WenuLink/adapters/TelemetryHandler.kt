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

    private val _isBroadcasting = MutableStateFlow(false)
    val isBroadcasting: StateFlow<Boolean> = _isBroadcasting.asStateFlow()

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

        if (isActive()) {
            logger.e { "Unable to set, Telemetry is active." }
            return
        }

        if (!isSimulationReady()) {
            logger.e { "Unable to set, Simulation is not available." }
            return
        }

        if (runSimulation == enable) return

        runSimulation = enable
    }

    fun registerSimState(register: Boolean) {
        // Always clear first
        SimManager.unregisterStateCallback()
        if (register) SimManager.registerStateCallback { state ->
            // TODO: Some telemetry values such as velocities must be updated
            if (lastTelemetryData != null)
                SimManager.completeTelemetryData(lastTelemetryData!!, state)
            updateTelemetryData(SimManager.state2telemetry(state))
        }
    }

    fun registerRealState(register: Boolean) {
        if (!AircraftManager.isAircraftConnected()) {
            logger.w { "Aircraft not connected. Not ready for telemetry." }
            return
        }

        // Always clear first
        FCManager.unregisterStateCallback()
        if (register) FCManager.registerStateCallback { state ->
            // TODO: positionX,Y,Z values must be updated
            updateTelemetryData(FCManager.state2telemetry(state))
        }
    }

    @Synchronized
    fun registerStateListeners(register: Boolean) {
        logger.d { "registerStateListeners: $register (runSimulation: $runSimulation)" }
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
            if (error == null) logger.i { "Simulation is running." }
            else logger.e { "Unable to start simulation: $error" }
        }
        else SimManager.stop { error ->
            if (error == null) logger.i { "Simulation is stop." }
            else logger.e { "Unable to stop simulation: $error" }
        }
    }

    fun listenVehicleState(listen: Boolean) {
        if (runSimulation) listenSimulation(listen)
        else listenAircraft(listen)
    }

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
            isReadingData()

    fun registerHandlerScope(handlerScope: CoroutineScope) {
        isListeningRC.distinctUntilChangedBy { it }
            .onEach { listenRemoteController(it) }
            .launchIn(handlerScope)

        isListeningAircraft.distinctUntilChangedBy { it }
            .onEach { listenVehicleState(it) }
            .launchIn(handlerScope)

        isBroadcasting.distinctUntilChangedBy { it }
            .onEach {
                logger.d { "Requesting to ${if(it) "start" else "stop"} telemetry broadcast." }
                if (it) startBroadcast()
                else stopBroadcast()
            }
            .launchIn(handlerScope)
    }

    fun isReadingData(): Boolean {
        // RC should always exists
        var isFlowing = RCManager.isUpdated()
        // If simulation activated, must wait for its startup
        isFlowing = isFlowing &&
                if (runSimulation) isSimulationActive()
        // Assumes that if not sim, Aircraft is present
        else AircraftManager.isUpdated()
        return isFlowing
    }

    suspend fun waitTelemetryUp(timeout: Long = 5000L): Boolean {
        // First check wait for listeners transfer data
        val listenersOk = AsyncUtils.waitTimeout(timeout = timeout, isReady = ::isReadingData)
        if (!listenersOk) return false

        // Second wait to receive the data ready for broadcast
        return AsyncUtils.waitTimeout(timeout = timeout, isReady = ::hasTelemetryData)
    }

    suspend fun waitForTelemetryDown(timeout: Long = 5000L): Boolean {
        // Wait for listeners to stop receiving data
        fun stopReading() = !isReadingData()
        AsyncUtils.waitReady(timeout, isReady = ::stopReading)
        // reset the telemetry info
        updateTelemetryData(null)
        return !(isReadingData() || hasTelemetryData())
    }

    fun launchTelemetry(start: Boolean) {
        _isBroadcasting.value = start
    }

    fun getRCData(): RCData? = RCManager.getHardwareData()

    fun getAircraftBattery(): BatteryData {
        // TODO: Maybe include some custom battery level
        return if (runSimulation) RCManager.getBatteryData()
        else AircraftManager.getBatteryData()
    }

    fun getAirlinkSignal(): IntArray {
        return if (runSimulation) intArrayOf(98, 95)
        else AircraftManager.getAirlinkData()
    }

}
