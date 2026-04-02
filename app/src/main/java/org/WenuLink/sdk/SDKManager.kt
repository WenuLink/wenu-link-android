package org.WenuLink.sdk

import android.app.Application
import android.content.Context
import androidx.multidex.MultiDex
import com.cySdkyc.clx.Helper

object SDKManager {
    var isContextAttached = false
    var aircraftBindingState: String? = "Idle"
        private set
    var appActivationState: String? = "Idle"
        private set

    fun attachBaseContext(app: Application) {
        MultiDex.install(app)
        Helper.install(app)
        isContextAttached = true
    }

    fun getIntentAction(): String = SDKUtils.getUsbAction()

    fun isRegistered(): Boolean = RegistrationHandler.registered

    fun setActivationCallback(callback: (Boolean, String?) -> Unit) =
        RegistrationHandler.setActivationCallback(callback)

    fun setBindingCallback(callback: (Boolean, String?) -> Unit) =
        RegistrationHandler.setBindingCallback(callback)

    fun setProductChangeCallback(callback: (Boolean) -> Unit) =
        RegistrationHandler.setProductChangeCallback(callback)

    fun startRegistration(context: Context, callback: (Boolean, String?) -> Unit) {
        RegistrationHandler.setSuccessCallback(callback)
        RegistrationHandler.initialize(context)
    }

    fun destroy(context: Context) = // TODO: unload everything
        RegistrationHandler.destroy()

    fun getAircraftModel(): String =
        SDKUtils.getAircraftInstance()?.model?.displayName ?: "No Aircraft Detected"
}
