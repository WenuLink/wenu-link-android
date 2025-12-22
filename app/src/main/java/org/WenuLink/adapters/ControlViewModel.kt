package org.WenuLink.adapters

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.WenuLink.webrtc.WebRTCService
import org.WenuLink.mavlink.MAVLinkService
import org.WenuLink.sdk.SDKManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow

class ControlViewModel : ViewModel() {

    private val mavlink = MAVLinkService.getInstance()

    private val _isPermissionsGranted = MutableLiveData<Boolean>()
    val isPermissionsGranted: LiveData<Boolean> = _isPermissionsGranted

    private val _workflowStatus = MutableLiveData<String>()
    val workflowStatus: LiveData<String> = _workflowStatus

    private val _sdkStatus = MutableLiveData<String>()
    val sdkStatus: LiveData<String> = _sdkStatus

    private val _isRegistered = MutableLiveData<Boolean>(SDKManager.isRegistered())
    val isRegistered: LiveData<Boolean> = _isRegistered

    private val _bindingState = MutableLiveData<String>()
    val bindingState: LiveData<String> = _bindingState

    private val _activationState = MutableLiveData<String>()
    val activationState: LiveData<String> = _activationState

    private val _isServiceRunning = MutableLiveData<Boolean>(mavlink.isServiceUp)
    val isServiceRunning: LiveData<Boolean> = _isServiceRunning

    private val _canRunService = MutableLiveData<Boolean>(mavlink.isServiceUp)
    val canRunService: LiveData<Boolean> = _canRunService

    private val _telemetryData = MutableLiveData<TelemetryData?>()
    val telemetryData: LiveData<TelemetryData?> = _telemetryData

//    private val _isDataFlowing = MutableStateFlow(false)
    val isDataFlowing: StateFlow<Boolean> = mavlink.getTelemetryFlow()

    val isMAVLinkRunning: StateFlow<Boolean> = MAVLinkService.isRunning
    val isWebRTCRunning: StateFlow<Boolean> = WebRTCService.isRunning

    fun updatePermission(granted: Boolean) {
        _isPermissionsGranted.postValue(granted)
    }

    fun updateWorkflow(text: String) {
        _workflowStatus.postValue(text)
    }

    fun setSDKCallbacks() {
        // Register callbacks
        SDKManager.setActivationCallback { success, msg ->
            CoroutineScope(Dispatchers.IO).launch {
                Log.d("ControlViewModel", "activationChanged $success, $msg")
                _activationState.postValue(SDKManager.appActivationState)
            }
        }

        SDKManager.setBindingCallback { success, msg ->
            viewModelScope.launch {
                Log.d("ControlViewModel", "bindingChanged $success, $msg")
                _bindingState.postValue(SDKManager.aircraftBindingState)
            }
        }

        SDKManager.setProductChangeCallback { success ->
            viewModelScope.launch {
                if (success) {
                    _sdkStatus.postValue("Connected to ${SDKManager.getAircraftModel()}.")
                    updateWorkflow("Waiting for telemetry")
                    _canRunService.postValue(true)
                } else {
                    _canRunService.postValue(false)
                }
            }
        }

        _activationState.postValue(SDKManager.appActivationState)
        _bindingState.postValue(SDKManager.aircraftBindingState)
    }

    fun startSDK(context: Context) {
        updateWorkflow("Initializing SDK")
        setSDKCallbacks()

        SDKManager.startRegistration(context) { success, error ->
            viewModelScope.launch {
                _isRegistered.postValue(success)
                if (success) {
                    updateWorkflow("Waiting for Aircraft")
                    _sdkStatus.postValue("Registered")
                } else {
                    updateWorkflow("Error during SDK registration")
                    _sdkStatus.postValue("$error")
                }
            }
        }
    }

    fun stopSDK(context: Context) {
        SDKManager.destroy(context)
    }

    fun initMAVLinkCallbacks() {
        mavlink.registerStartCallback { error ->
            viewModelScope.launch {
                if (error == null) updateWorkflow("MAVLink Service's up")
                else updateWorkflow(error)
            }
        }

        mavlink.registerStopCallback { error ->
            viewModelScope.launch {
                if (error == null) updateWorkflow("MAVLink Service's down")
                else updateWorkflow(error)
            }
        }
    }

    fun runService(run: Boolean, context: Context) {
        if (run) DroneService.start(context)
        else DroneService.stop(context)
        _isServiceRunning.postValue(run)
    }

    fun runMAVLink(isRunning: Boolean) {
        // TODO: Update GCS server address from user input
        // mavlink.initClient("192.168.1.220", 14550)
        viewModelScope.launch {
            MAVLinkService.runProcess(isRunning)
        }
    }

    fun runWebRTC(isRunning: Boolean) {
        // TODO: Update signaling server address from user input
        // WebRTCService.getInstance().updateServerAddress("ws://192.168.1.220:8090")
        viewModelScope.launch {
            WebRTCService.runProcess(isRunning)
        }
    }

    fun isSimulationReady(): Boolean = mavlink.isSimulationReady()

    fun isSimulationActive(): Boolean = mavlink.isSimulationActive()

    fun enableSimulation(enable: Boolean) {
        mavlink.enableSimulation(enable)
    }
}
