package org.WenuLink.views

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.WenuLink.WenuLinkApp
import org.WenuLink.adapters.DroneService
import org.WenuLink.adapters.TelemetryData
import org.WenuLink.adapters.TelemetryHandler


class ServicesViewModel(application: Application) : AndroidViewModel(application) {

    private var thisApp = (getApplication() as WenuLinkApp)

    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning

    private val _telemetryData = MutableLiveData<TelemetryData?>()
    val telemetryData: LiveData<TelemetryData?> = _telemetryData

    // To expose the StateFlow for MAVLink
    val isMAVLinkRunning: StateFlow<Boolean>
        get() = thisApp.droneService?.getMAVLinkState() ?: MutableStateFlow(false)

    // To expose the StateFlow for WebRTC
    val isWebRTCRunning: StateFlow<Boolean>
        get() = thisApp.droneService?.getWebRTCState() ?: MutableStateFlow(false)

    val isDataFlowing: StateFlow<Boolean> = TelemetryHandler.getInstance().isDataFlowing

    fun isSimulationReady(): Boolean = TelemetryHandler.getInstance().isSimulationReady()

    fun isSimulationActive(): Boolean = TelemetryHandler.getInstance().isSimulationActive()

    fun enableSimulation(enable: Boolean) = TelemetryHandler.getInstance().enableSimulation(enable)

    fun startService() {
        if (thisApp.droneService == null) {
            val startFunction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                thisApp::startForegroundService
            } else {
                thisApp::startService
            }
            startFunction(Intent(thisApp, DroneService::class.java))
        }
    }

    fun stopService() {
        if (thisApp.droneService != null) {
            thisApp.stopService(Intent(thisApp, DroneService::class.java))
            thisApp.droneService = null // Clear the reference
        }
    }

    fun runMAVLink(run: Boolean) {
        // TODO: Update GCS server address from user input
        // mavlink.initClient("192.168.1.220", 14550)
        viewModelScope.launch {
            if (run) thisApp.droneService?.startMAVLink()
            else thisApp.droneService?.stopMAVLink()
        }
    }

    fun runWebRTC(run: Boolean) {
        // TODO: Update signaling server address from user input
        // WebRTCService.getInstance().updateServerAddress("ws://192.168.1.220:8090")
        viewModelScope.launch {
            if (run) thisApp.droneService?.startWebRTC()
            else thisApp.droneService?.stopWebRTC()
        }
    }

    fun runService(run: Boolean) {
        viewModelScope.launch {
            if (run) startService()
            else stopService()
            var isRunning = thisApp.droneService?.isMAVLinkUp() ?: false
            isRunning = isRunning || thisApp.droneService?.isWebRTCUp() ?: false
            _isServiceRunning.value = isRunning
        }
    }
}
