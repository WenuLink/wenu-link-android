package org.WenuLink.sdk

import android.content.Context
import dji.common.error.DJIError
import dji.common.error.DJISDKError
import dji.common.product.Model
import dji.common.realname.AircraftBindingState
import dji.common.realname.AircraftBindingState.AircraftBindingStateListener
import dji.common.realname.AppActivationState
import dji.common.realname.AppActivationState.AppActivationStateListener
import dji.sdk.airlink.AirLink
import dji.sdk.base.BaseComponent
import dji.sdk.base.BaseProduct
import dji.sdk.camera.Camera
import dji.sdk.flightcontroller.FlightController
import dji.sdk.products.Aircraft
import dji.sdk.realname.AppActivationManager
import dji.sdk.remotecontroller.RemoteController
import dji.sdk.sdkmanager.DJISDKInitEvent
import dji.sdk.sdkmanager.DJISDKManager
import io.getstream.log.taggedLogger
import org.WenuLink.commands.CommandResult
import org.WenuLink.commands.UnitResult

object APIManager {
    private val logger by taggedLogger(APIManager::class.java.simpleName)
    private val loggerC by taggedLogger(DJISDKManager.SDKManagerCallback::class.java.simpleName)
    private val activationManager: AppActivationManager? =
        DJISDKManager.getInstance().appActivationManager
    private var registrationInProgress: Boolean = false
    private var activationStateListener: AppActivationStateListener? = null
    private var bindingStateListener: AircraftBindingStateListener? = null
    private var sdkManagerCallback: DJISDKManager.SDKManagerCallback? = null
    val isSimulationAvailable get() = SimManager.isAvailable()

    fun getIntentAction(): String = DJISDKManager.USB_ACCESSORY_ATTACHED

    @Synchronized
    fun initialize(context: Context) {
        if (!registrationInProgress) {
            logger.d { "Starting registration..." }
            registrationInProgress = true
//            eventBus.register(context)
            DJISDKManager.getInstance().registerApp(context, sdkManagerCallback)
        }
    }

    fun areComponentsPresent() =
        AircraftManager.isConnected() && FCManager.isConnected() && CameraManager.isConnected()

    private fun setActivationCallback(callback: (Boolean, String?) -> Unit) {
        activationStateListener =
            AppActivationStateListener { appActivationState: AppActivationState ->
                callback(true, "$appActivationState")
            }
        activationManager?.addAppActivationStateListener(
            activationStateListener as AppActivationStateListener
        )
    }

    private fun setBindingCallback(callback: (Boolean, String?) -> Unit) {
        bindingStateListener = AircraftBindingStateListener { bindingState: AircraftBindingState ->
            callback(true, "$bindingState")
        }
        activationManager?.addAircraftBindingStateListener(
            bindingStateListener as AircraftBindingStateListener
        )
    }

    private fun tearDownListener() {
        if (activationStateListener != null) {
            // Example of removing listeners
            activationManager?.removeAppActivationStateListener(
                activationStateListener as AppActivationStateListener
            )
        }
        if (bindingStateListener != null) {
            activationManager?.removeAircraftBindingStateListener(
                bindingStateListener as AircraftBindingStateListener
            )
        }
    }

    @Synchronized
    fun destroy() {
        logger.d { "Stopping.." }
        tearDownListener()
        DJISDKManager.getInstance().destroy()
        sdkManagerCallback = null
    }

    private fun updateAircraftInstance(instance: BaseProduct?): Boolean {
        logger.i { "Product present: $instance" }

        return instance
            ?.takeIf { it is Aircraft && it.model != Model.UNKNOWN_AIRCRAFT }
            ?.also { aircraft ->
                logger.i { "Model: ${aircraft.model}" }
            }
            ?.model
            ?.let {
                AircraftManager.init(instance as Aircraft)
                logger.i { "Aircraft: $instance" }
                true
            }
            ?: false
    }

    fun registerCallbacks(
        registrationCallback: (UnitResult) -> Unit,
        productConnectedCallback: (Boolean) -> Unit,
        activationCallback: (Boolean, String?) -> Unit = { _, _ -> },
        bindingCallback: (Boolean, String?) -> Unit = { _, _ -> }
    ) {
        setActivationCallback(activationCallback)
        setBindingCallback(bindingCallback)

        sdkManagerCallback = object : DJISDKManager.SDKManagerCallback {
            override fun onRegister(djiError: DJIError) {
                if (djiError == DJISDKError.REGISTRATION_SUCCESS) {
                    DJISDKManager.getInstance().startConnectionToProduct()
                    loggerC.i { "Successfully registered" }
                    registrationCallback(CommandResult.ok)
                } else {
                    val errorDescription = djiError.description
                    loggerC.i { "Error in registration, $errorDescription" }
                    DJISDKManager.getInstance().destroy()
                    registrationCallback(CommandResult.error(errorDescription))
                }
                registrationInProgress = false
            }

            override fun onProductDisconnect() {
                loggerC.i { "Drone disconnected" }
                productConnectedCallback(false)
            }

            override fun onProductConnect(product: BaseProduct?) {
                loggerC.i { "onProductConnect()" }
                productConnectedCallback(updateAircraftInstance(product))
            }

            override fun onProductChanged(product: BaseProduct?) {
                loggerC.i { "onProductChanged()" }
                productConnectedCallback(updateAircraftInstance(product))
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
                if (componentKey == BaseProduct.ComponentKey.REMOTE_CONTROLLER) {
                    RCManager.init(newComponent as RemoteController)
                }
                if (componentKey == BaseProduct.ComponentKey.AIR_LINK) {
                    AircraftManager.initAirLink(newComponent as AirLink)
                }
                if (componentKey == BaseProduct.ComponentKey.FLIGHT_CONTROLLER) {
                    FCManager.init(newComponent as FlightController)
                }
                if (componentKey == BaseProduct.ComponentKey.CAMERA) {
                    CameraManager.init(newComponent as Camera)
                }
            }

            override fun onInitProcess(p0: DJISDKInitEvent?, p1: Int) {
                // Handle initialization process if needed
            }

            override fun onDatabaseDownloadProgress(p0: Long, p1: Long) {
                // Handle database download progress if needed
            }
        }
    }
}
