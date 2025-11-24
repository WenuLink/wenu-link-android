package org.WenuLink.adapters

import org.WenuLink.sdk.AircraftManager
import org.WenuLink.sdk.FCManager
import org.WenuLink.sdk.RCManager
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

        fun getInstance(): TelemetryHandler {
            if (mInstance == null)
                mInstance = TelemetryHandler()
            return mInstance!!
        }

        private val _isDataFlowing = MutableStateFlow(false)
        val isDataFlowing: StateFlow<Boolean> = _isDataFlowing.asStateFlow()

    }
    private val logger by taggedLogger("TelemetryHandler")

    private val _isListeningRC = MutableStateFlow(false)
    val isListeningRC: StateFlow<Boolean> = _isListeningRC.asStateFlow()
    private val _isListeningAircraft = MutableStateFlow(false)
    val isListeningAircraft: StateFlow<Boolean> = _isListeningAircraft.asStateFlow()
    private lateinit var handlerScope: CoroutineScope //(Dispatchers.Main + Job())
    @Volatile private var lastTelemetryData: TelemetryData? = null

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

    fun registerStateListeners(register: Boolean) {
        if (!AircraftManager.isAircraftConnected()) {
            logger.w { "Aircraft not connected. Not ready for telemetry." }
            return
        }

        if (register) FCManager.registerStateCallback { state ->
            updateTelemetryData(FCManager.state2telemetry(state))
        }
        else FCManager.unregisterStateCallback()
    }

    @Synchronized
    fun run() {
        if (hasTelemetryData()) {
            logger.w { "Telemetry already flowing!" }
            return
        }

        logger.i { "Starting Telemetry." }
        registerStateListeners(true)
    }

    @Synchronized
    fun stop() {
        if (!hasTelemetryData()) {
            logger.w { "No telemetry data to stop!" }
            return
        }
        logger.i { "Stopping Telemetry." }
        registerStateListeners(false)
        updateTelemetryData(null)
    }

    fun listenRemoteController(listen: Boolean) {
        if (listen) RCManager.startListeners()
        else RCManager.stopListeners()
    }

    fun listenAircraft(listen: Boolean) {
        if (listen) AircraftManager.startListeners()
        else AircraftManager.stopListeners()
    }

    fun registerListenerScope(serviceScope: CoroutineScope) {
        handlerScope = serviceScope

        isListeningRC.distinctUntilChangedBy { it }
            .onEach { listenRemoteController(it) }
            .launchIn(handlerScope)

        isListeningAircraft.distinctUntilChangedBy { it }
            .onEach { listenAircraft(it) }
            .launchIn(handlerScope)

        isDataFlowing.distinctUntilChangedBy { it }
            .onEach {
                if (it) run()
                else stop()
                logger.d { "isDataFlowing: $it" }
            }
            .launchIn(handlerScope)
    }

    fun hasListenersData(): Boolean {
        // must always exist RC and AircraftS
        return RCManager.isUpdated().and(AircraftManager.isUpdated())
    }

    fun start(start: Boolean, onResult: (Boolean, String?) -> Unit) {
        _isListeningRC.value = start
        _isListeningAircraft.value = start

        if (start) {
            handlerScope.launch {
                checkReadiness(isReady = ::hasListenersData) { success ->
                    if (success) {
                        _isDataFlowing.value = true
                    } else {
                        onResult(false, "Telemetry cannot start yet!") // Indicate failure
                    }
                }
                checkReadiness(isReady = ::hasTelemetryData) { success ->
                    if (success) {
                        onResult(true, null) // Indicate success
                    } else {
                        onResult(false, "No telemetry captured yet!") // Indicate failure
                    }
                }
            }
        }
        else {
            _isDataFlowing.value = false
            onResult(true, null) // Indicate success
        }
    }

    fun getRCData(): RCData? = RCManager.getHardwareData()

    fun getAircraftBattery(): BatteryData = AircraftManager.getBatteryData()

    fun getHomePosition(): Triple<Double, Double, Int>? {
        // check for home location and set
        val homeLocation = FCManager.getHomePosition()
        if (homeLocation == null){
            logger.i { "Home position not set, trying current aircraft position." }
            FCManager.setHomePosition { error ->
                if (error == null) logger.i { "Home position set OK" }
                else logger.w { "Unable to set home position: $error" }
            }
        }
        return homeLocation
    }
}
