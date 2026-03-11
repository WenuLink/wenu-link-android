package org.WenuLink.sdk

import android.content.Context
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
import io.getstream.log.taggedLogger

object RegistrationHandler {
    private val logger by taggedLogger(RegistrationHandler::class.java.simpleName)
    private val loggerC by taggedLogger(DJISDKManager.SDKManagerCallback::class.java.simpleName)
    private var registrationInProgress: Boolean = false
    private var registered: Boolean = false
    private var successCallback: ((Boolean, String?) -> Unit)? = null
    private var productCallback: ((Boolean) -> Unit)? = null
    private var activationStateListener: AppActivationStateListener? = null
    private var bindingStateListener: AircraftBindingStateListener? = null

    fun isRegistered(): Boolean = registered

    fun setSuccessCallback(callback: (Boolean, String?) -> Unit) {
        successCallback = callback
    }

    fun setProductChangeCallback(callback: (Boolean) -> Unit) {
        productCallback = callback
    }

    @Synchronized
    fun initialize(context: Context) {
        if (!registrationInProgress) {
            logger.d { "Starting registration..." }
            registrationInProgress = true
//            eventBus.register(context)
            DJISDKManager.getInstance().registerApp(context, sdkManagerCallback)
        }
    }

    fun setActivationCallback(callback: (Boolean, String?) -> Unit) {
        activationStateListener = AppActivationStateListener {
                appActivationState: AppActivationState -> callback(true, "$appActivationState") }
        SDKUtils.getAppActivationManager()?.addAppActivationStateListener(
            activationStateListener as AppActivationStateListener)
    }

    fun setBindingCallback(callback: (Boolean, String?) -> Unit) {
        bindingStateListener = AircraftBindingStateListener {
                bindingState: AircraftBindingState -> callback(true, "$bindingState") }
        SDKUtils.getAppActivationManager()?.addAircraftBindingStateListener(
            bindingStateListener as AircraftBindingStateListener)
    }

    fun tearDownListener() {
        if (activationStateListener != null) {
            // Example of removing listeners
            SDKUtils.getAppActivationManager()?.removeAppActivationStateListener(
                activationStateListener as AppActivationStateListener)
        }
        if (bindingStateListener != null) {
            SDKUtils.getAppActivationManager()?.removeAircraftBindingStateListener(
                bindingStateListener as AircraftBindingStateListener)
        }
    }

    @Synchronized
    fun destroy() {
        logger.d { "Stopping.." }
        tearDownListener()
        registered = false
        DJISDKManager.getInstance().destroy()
    }

    private val sdkManagerCallback: DJISDKManager.SDKManagerCallback = object :
        DJISDKManager.SDKManagerCallback {
        override fun onRegister(djiError: DJIError) {
            if (djiError == DJISDKError.REGISTRATION_SUCCESS) {
                DJISDKManager.getInstance().startConnectionToProduct()
                loggerC.i { "Successfully registration" }
                successCallback?.invoke(true, null)
                registered = true
            } else {
                val errorDescription = djiError.description
                loggerC.i { "Error in registration, $errorDescription" }
                DJISDKManager.getInstance().destroy()
                successCallback?.invoke(false, errorDescription)
            }
            registrationInProgress = false
        }

        override fun onProductDisconnect() {
            loggerC.i { "Drone disconnected" }
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
            loggerC.i { "onProductChanged()" }
            productCallback?.invoke(true)
        }

        override fun onComponentChange(
            componentKey: BaseProduct.ComponentKey?,
            oldComponent: BaseComponent?,
            newComponent: BaseComponent?
        ) {
            newComponent?.setComponentListener { isConnected ->
                loggerC.d { "onComponentConnectivityChanged: $isConnected" }
            }
            loggerC.d {
                "onComponentChange key: $componentKey, " +
                "oldComponent: $oldComponent, " +
                "newComponent: $newComponent"
            }
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