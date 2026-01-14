package org.WenuLink.views

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.getstream.log.taggedLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.WenuLink.WenuLinkApp
import org.WenuLink.adapters.WenuLinkService
import org.WenuLink.adapters.TelemetryData
import org.WenuLink.adapters.TelemetryHandler
import org.WenuLink.adapters.Utils
import kotlin.getValue


class ServicesViewModel(application: Application) : AndroidViewModel(application) {
    private val logger by taggedLogger("ServicesViewModel")

    private var thisApp = (getApplication() as WenuLinkApp)

    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning

    private val _telemetryData = MutableLiveData<TelemetryData?>()
    val telemetryData: LiveData<TelemetryData?> = _telemetryData

    // To expose the StateFlow for MAVLink
    val isMAVLinkRunning: StateFlow<Boolean>
        get() = thisApp.wenuLinkService?.getMAVLinkState() ?: MutableStateFlow(false)

    // To expose the StateFlow for WebRTC
    val isWebRTCRunning: StateFlow<Boolean>
        get() = thisApp.wenuLinkService?.getWebRTCState() ?: MutableStateFlow(false)

    val isDataFlowing: StateFlow<Boolean> = TelemetryHandler.getInstance().isBroadcasting

    fun isSimulationReady(): Boolean = TelemetryHandler.getInstance().isSimulationReady()

    fun isSimulationActive(): Boolean = TelemetryHandler.getInstance().isSimulationActive()

    fun enableSimulation(enable: Boolean) = viewModelScope.launch {
        // TODO check for required conditions
        TelemetryHandler.getInstance().enableSimulation(enable)
//        thisApp.droneService?.enableSimulation(enable)
        // TODO: updateSimulationState?
    }

    fun isServiceRunning(): Boolean = thisApp.wenuLinkService?.isRunning() ?: false

    fun isServiceReady(): Boolean = thisApp.wenuLinkService?.isReady() ?: false

    fun isServiceStop() = !isServiceRunning()

    fun startService() {
        if (thisApp.wenuLinkService != null) return

        val startFunction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            thisApp::startForegroundService
        } else {
            thisApp::startService
        }
        startFunction(Intent(thisApp, WenuLinkService::class.java))

        viewModelScope.launch {
            _isServiceRunning.value = Utils.waitTimeout(1000, 60000, ::isServiceReady)
        }
    }

    fun stopService() {
        if (thisApp.wenuLinkService == null) return

        // Stop services and wait for shutdown
        runMAVLink(false)
        runWebRTC(false)
        viewModelScope.launch {
            Utils.waitReady(isReady = ::isServiceStop)
            thisApp.stopService(
                Intent(thisApp, WenuLinkService::class.java)
            )
            _isServiceRunning.value = false
        }
    }

    fun runMAVLink(run: Boolean) {
        // TODO: Update GCS server address from user input
        // mavlink.initClient("192.168.1.220", 14550)
        viewModelScope.launch {
            if (run) thisApp.wenuLinkService?.startMAVLinkService()
            else thisApp.wenuLinkService?.stopMAVLinkService()
        }
    }

    fun runWebRTC(run: Boolean) {
        // TODO: Update signaling server address from user input
        // WebRTCService.getInstance().updateServerAddress("ws://192.168.1.220:8090")
        viewModelScope.launch {
            if (run) thisApp.wenuLinkService?.startWebRTCService()
            else thisApp.wenuLinkService?.stopWebRTCService()
        }
    }

    fun runService(run: Boolean) {
        logger.d { "runService($run)" }
        if (run) startService()
        else stopService()
    }
}
