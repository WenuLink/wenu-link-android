package org.WenuLink.views

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.getstream.log.taggedLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.WenuLink.WenuLinkApp
import org.WenuLink.adapters.AsyncUtils
import org.WenuLink.adapters.aircraft.AircraftHandler
import org.WenuLink.adapters.aircraft.TelemetryData

class ServicesViewModel(application: Application) : AndroidViewModel(application) {
    private val logger by taggedLogger(ServicesViewModel::class.java.simpleName)

    private var thisApp = (getApplication() as WenuLinkApp)

    private val aircraft: AircraftHandler = AircraftHandler.getInstance()

    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning

    val isDataFlowing: StateFlow<Boolean> = aircraft.telemetry.isBroadcasting

    private val _telemetryData = MutableLiveData<TelemetryData?>()
    val telemetryData: LiveData<TelemetryData?> = _telemetryData

    // To expose the StateFlow for MAVLink
    val isMAVLinkRunning: StateFlow<Boolean>
        get() = thisApp.wenuLinkService?.mavlinkStateFlow ?: MutableStateFlow(false)

    // To expose the StateFlow for WebRTC
    val isWebRTCRunning: StateFlow<Boolean>
        get() = thisApp.wenuLinkService?.webRTCStateFlow ?: MutableStateFlow(false)

    private val _isSimReady = MutableStateFlow(false)
    val isSimReady: StateFlow<Boolean> = _isSimReady.asStateFlow()

    init {
        viewModelScope.launch {
            // Wait for flag change
            _isSimReady.value = AsyncUtils.waitTimeout(
                1000,
                60000,
                ::isSimulationReady
            )
        }
    }

    fun isSimulationReady(): Boolean = aircraft.telemetry.isSimulationAvailable

    fun isServiceReady(): Boolean = thisApp.wenuLinkService?.isReady() ?: false

    fun startService() {
        thisApp.launchWenulinkService()
        // Async wait and update for state change
        viewModelScope.launch {
            _isServiceRunning.value = AsyncUtils.waitTimeout(
                1000,
                60000,
                ::isServiceReady
            )
            thisApp.wenuLinkService?.runServices { error ->
                logger.d { "runServices error: $error" }
            }
        }
    }

    fun stopService() {
        if (thisApp.wenuLinkService == null) return

        // Stop services and wait for shutdown
        viewModelScope.launch {
            thisApp.wenuLinkService?.terminate()
            aircraft.telemetry.enableSimulation(false) // always disable
            thisApp.stopWenulinkService()
            _isServiceRunning.value = false
        }
    }

    fun forceStop() {
        if (thisApp.wenuLinkService == null) return
        viewModelScope.launch {
            thisApp.wenuLinkService?.stopCommands()
        }
    }

    fun runMAVLink(run: Boolean) {
        // TODO: Update GCS server address from user input
        // mavlink.initClient("192.168.1.220", 14550)
        viewModelScope.launch {
            if (run) {
                thisApp.wenuLinkService?.startMAVLinkService { error ->
                    logger.d { "startMAVLinkService error: $error" }
                }
            } else {
                thisApp.wenuLinkService?.stopMAVLinkService()?.join()
            }
        }
    }

    fun runWebRTC(run: Boolean) {
        // TODO: Update signaling server address from user input
        // WebRTCService.getInstance().updateServerAddress("ws://192.168.1.220:8090")
        viewModelScope.launch {
            if (run) {
                thisApp.wenuLinkService?.startWebRTCService()
            } else {
                thisApp.wenuLinkService?.stopWebRTCService()
            }
        }
    }

    fun runService(run: Boolean, simEnabled: Boolean = false) {
        logger.d { "runService($run)" }
        if (simEnabled) aircraft.telemetry.enableSimulation(true)
        if (run) {
            startService()
        } else {
            stopService()
        }
    }
}
