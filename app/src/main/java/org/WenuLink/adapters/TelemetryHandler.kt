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
    fun run() {
        if (!FCManager.isConnected()) {
            logger.w { "Flight controller not present. Not ready for telemetry." }
            return
        }

        if (!AircraftManager.isAircraftConnected()) {
            logger.w { "Aircraft not connected. Not ready for telemetry." }
            return
        }

        if (isFlowing()) {
            logger.w { "Telemetry already flowing!" }
            return
        }

        logger.i { "Starting Telemetry." }
        FCManager.startReadingState()
    }

    @Synchronized
    fun stop() {
        if (!isFlowing()) {
            return
        }
        logger.i { "Stopping Telemetry." }
        FCManager.stopReadingState()
    }

    @Synchronized
    fun getRecent(): TelemetryData? = FCManager.getTelemetryData()

    fun registerListenerScope(serviceScope: CoroutineScope) {
        handlerScope = serviceScope

        if (RCManager.isRCConnected()) {
            isListeningRC.distinctUntilChangedBy { it }
                .onEach { listenRemoteController(it) }
                .launchIn(handlerScope)
        }
        if (AircraftManager.isAircraftConnected()) {
            isListeningAircraft.distinctUntilChangedBy { it }
                .onEach { listenAircraft(it) }
                .launchIn(handlerScope)
        }

        // Non-blocking logs of your flows
        isDataFlowing.distinctUntilChangedBy { it }
            .onEach {
                if (it) run()
                else stop()
                logger.d { "isDataFlowing: $it" }
            }
            .launchIn(handlerScope)
    }

    fun getRCData(): RCData? = RCManager.getHardwareData()

    fun listenRemoteController(listen: Boolean) {
        if (listen) RCManager.startListeners()
        else RCManager.stopListeners()
    }

    fun listenAircraft(listen: Boolean) {
        if (listen) AircraftManager.startListeners()
        else AircraftManager.stopListeners()
    }

    fun startListening(start: Boolean) {
        handlerScope.launch {
            _isListeningRC.value = start
            _isListeningAircraft.value = start
        }
    }

    fun hasUpdatedData(): Boolean {
        return RCManager.isUpdated() && AircraftManager.isUpdated()
    }

    fun isFlowing(): Boolean {
        return getRecent() != null
    }

    fun start(start: Boolean, onResult: (Boolean, String?) -> Unit) {
        if (start) {
            startListening(true)
            handlerScope.launch {
                checkReadiness(isReady = ::hasUpdatedData) { success ->
                    if (success) {
                        _isDataFlowing.value = true
                    } else {
                        onResult(false, "Telemetry cannot start yet!") // Indicate failure
                    }
                }
                checkReadiness(isReady = ::isFlowing) { success ->
                    if (success) {
                        onResult(true, null) // Indicate success
                    } else {
                        onResult(false, "Telemetry cannot flow yet!") // Indicate failure
                    }
                }
            }
        }
        else {
            _isDataFlowing.value = false
            startListening(false)
            onResult(true, null) // Indicate success
        }
    }

    fun getAircraftBattery(): BatteryData = AircraftManager.getBatteryData()

    fun getHomePosition(): Triple<Double, Double, Int>? {
        // check for home location and set
        val homeLocation = FCManager.getHomePosition()
        if (homeLocation == null){
            logger.i { "Home position not set, trying current aircraft position." }
            FCManager.setHomePosition { success ->
                if (success) logger.i { "Home position set OK" }
                else logger.w { "Unable to set home position" }
            }
        }
        return homeLocation
    }
}
