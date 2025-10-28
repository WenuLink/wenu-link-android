package org.WenuLink.sdk

import android.content.Context
import android.util.Log
import dji.common.error.DJIError
import dji.common.error.DJISDKError
import dji.common.realname.AircraftBindingState
import dji.common.realname.AircraftBindingState.AircraftBindingStateListener
import dji.common.realname.AppActivationState
import dji.common.realname.AppActivationState.AppActivationStateListener
import dji.sdk.airlink.AirLink
import dji.sdk.base.BaseComponent
import dji.sdk.base.BaseProduct
import dji.sdk.camera.Camera
import dji.sdk.flightcontroller.FlightController
import dji.sdk.remotecontroller.RemoteController
import dji.sdk.sdkmanager.DJISDKInitEvent
import dji.sdk.sdkmanager.DJISDKManager


object RegistrationHandler {
    private val TAG: String = RegistrationHandler::class.java.simpleName
    private val TAGC: String = DJISDKManager.SDKManagerCallback::class.java.simpleName
    private var registrationInProgress: Boolean = false
    private var registered: Boolean = false
    private var successCallback: ((Boolean, String?) -> Unit)? = null
    private var productCallback: ((Boolean) -> Unit)? = null
    private var activationStatelistener: AppActivationStateListener? = null
    private var bindingStateListener: AircraftBindingStateListener? = null

    fun isRegistered(): Boolean {
        return registered
    }

    fun setSuccessCallback(callback: (Boolean, String?) -> Unit) {
        successCallback = callback
    }

    fun setProductChangeCallback(callback: (Boolean) -> Unit) {
        productCallback = callback
    }

    @Synchronized
    fun initialize(context: Context) {
        if (!registrationInProgress) {
            Log.d(TAG, "Starting registration...")
            registrationInProgress = true
//            eventBus.register(context)
            DJISDKManager.getInstance().registerApp(context, sdkManagerCallback)
        }
    }

    fun setActivationCallback(callback: (Boolean, String?) -> Unit) {
        activationStatelistener = AppActivationStateListener {
                appActivationState: AppActivationState -> callback(true, "$appActivationState") }
        SDKUtils.getAppActivationManager()?.addAppActivationStateListener(
            activationStatelistener as AppActivationStateListener)
    }

    fun setBindingCallback(callback: (Boolean, String?) -> Unit) {
        bindingStateListener = AircraftBindingStateListener {
                bindingState: AircraftBindingState -> callback(true, "$bindingState") }
        SDKUtils.getAppActivationManager()?.addAircraftBindingStateListener(
            bindingStateListener as AircraftBindingStateListener)
    }

    fun tearDownListener() {
        if (activationStatelistener != null) {
            // Example of removing listeners
            SDKUtils.getAppActivationManager()?.removeAppActivationStateListener(
                activationStatelistener as AppActivationStateListener);
        }
        if (bindingStateListener != null) {
            SDKUtils.getAppActivationManager()?.removeAircraftBindingStateListener(
                bindingStateListener as AircraftBindingStateListener);
        }
    }

    @Synchronized
    fun destroy() {
        Log.d(TAG, "Stopping..")
        tearDownListener()
        registered = false
        DJISDKManager.getInstance().destroy()
    }

    private val sdkManagerCallback: DJISDKManager.SDKManagerCallback = object :
        DJISDKManager.SDKManagerCallback {
        override fun onRegister(djiError: DJIError) {
            if (djiError == DJISDKError.REGISTRATION_SUCCESS) {
                DJISDKManager.getInstance().startConnectionToProduct()
                Log.i(TAGC, "Successfully registration")
                successCallback?.invoke(true, null)
                registered = true
            } else {
                val errorDescription = djiError.description
                Log.i(TAGC, "Error in registration, $errorDescription")
                DJISDKManager.getInstance().destroy()
                successCallback?.invoke(false, errorDescription)
            }
            registrationInProgress = false
        }

        override fun onProductDisconnect() {
            Log.i(TAGC, "Drone disconnected")
            productCallback?.invoke(false)
        }

        override fun onProductConnect(product: BaseProduct?) {
            val possibleAircraft = SDKUtils.getAircraftInstance()
            if (possibleAircraft != null)
                AircraftManager.init(possibleAircraft)
            //            updateProductInstance(product)
            productCallback?.invoke(true)
        }

        override fun onProductChanged(p0: BaseProduct?) {
            Log.i(TAGC, "onProductChanged()")
            productCallback?.invoke(true)
        }

        override fun onComponentChange(
            componentKey: BaseProduct.ComponentKey?,
            oldComponent: BaseComponent?,
            newComponent: BaseComponent?
        ) {
            newComponent?.setComponentListener(object : BaseComponent.ComponentListener {
                override fun onConnectivityChange(isConnected: Boolean) {
                    Log.d(TAGC, "onComponentConnectivityChanged: $isConnected")
                }
            })
            Log.d(
                TAGC,
                "onComponentChange key: $componentKey, oldComponent: $oldComponent, newComponent: $newComponent"
            )
            if (componentKey == BaseProduct.ComponentKey.REMOTE_CONTROLLER)
                RCManager.init(newComponent as RemoteController)
            if (componentKey == BaseProduct.ComponentKey.AIR_LINK)
                AircraftManager.initAirLink(newComponent as AirLink)
            if (componentKey == BaseProduct.ComponentKey.FLIGHT_CONTROLLER)
                FCManager.init(newComponent as FlightController)
            if (componentKey == BaseProduct.ComponentKey.CAMERA)
                CameraManager.init(newComponent as Camera)
        }

        override fun onInitProcess(p0: DJISDKInitEvent?, p1: Int) {
            // Handle initialization process if needed
        }

        override fun onDatabaseDownloadProgress(p0: Long, p1: Long) {
            // Handle database download progress if needed
        }
    }
}