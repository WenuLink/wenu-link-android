package org.WenuLink.views

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.getstream.log.taggedLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.WenuLink.WenuLinkApp

class ServicesViewModel(application: Application) : AndroidViewModel(application) {
    private val logger by taggedLogger(ServicesViewModel::class.java.simpleName)

    private var thisApp = (getApplication() as WenuLinkApp)

    // Basic status reporting
    val isPermissionsGranted: StateFlow<Boolean> = thisApp.isPermissionsGranted.asStateFlow()
    val workflowStatus: StateFlow<String> = thisApp.workflowStatus.asStateFlow()
    val sdkStatus: StateFlow<String> = thisApp.sdkStatus.asStateFlow()

    // SDK related reporting
    val isRegistered: StateFlow<Boolean> = thisApp.isRegistered.asStateFlow()
    val isAircraftPresent: StateFlow<Boolean> = thisApp.isAircraftPresent.asStateFlow()
    val isSimReady: StateFlow<Boolean> = thisApp.isSimulationReady.asStateFlow()
    val isAircraftBoot: StateFlow<Boolean> = thisApp.isAircraftBoot.asStateFlow()
    val isServiceUp: StateFlow<Boolean> = thisApp.isServiceUp.asStateFlow()

    // To expose the StateFlow for MAVLink
    val isMAVLinkRunning: StateFlow<Boolean>
        get() = thisApp.wenuLinkService?.mavlinkStateFlow ?: MutableStateFlow(false)

    // To expose the StateFlow for WebRTC
    val isWebRTCRunning: StateFlow<Boolean>
        get() = thisApp.wenuLinkService?.webRTCStateFlow ?: MutableStateFlow(false)

    fun forceStop() {
        viewModelScope.launch {
            thisApp.wenuLinkService?.stopCommands()
        }
    }

    fun runMAVLink(run: Boolean) {
        // TODO: Update GCS server address from user input
        // mavlink.initClient("192.168.1.220", 14550)
        viewModelScope.launch {
            if (run) {
                thisApp.wenuLinkService?.startMAVLinkService { result ->
                    if (result.hasError) logger.d { "startMAVLinkService error: $result" }
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

    fun runService(run: Boolean) = viewModelScope.launch {
        logger.d { "runService($run)" }
        if (run) {
            // Launch and wait for state update
            thisApp.launchWenulinkService()
        } else {
            // Stop services and wait for shutdown
            thisApp.stopWenulinkService()
        }
    }

    fun loadAircraft(load: Boolean, simEnabled: Boolean = false) = viewModelScope.launch {
        if (load) {
            thisApp.connectAircraft(simEnabled)
        } else {
            thisApp.disconnectAircraft()
        }
    }
}
