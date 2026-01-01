package org.WenuLink.adapters

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.WenuLink.sdk.SDKManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ControlViewModel : ViewModel() {

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
}
