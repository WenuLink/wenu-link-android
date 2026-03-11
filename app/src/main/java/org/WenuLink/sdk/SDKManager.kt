package org.WenuLink.sdk

import android.app.Application
import android.content.Context
import androidx.multidex.MultiDex
import com.cySdkyc.clx.Helper

object SDKManager {
    private val TAG: String = SDKManager::class.java.simpleName
    var isContextAttached: Boolean = false
    var aircraftBindingState: String? = "Idle"
        private set
    var appActivationState: String? = "Idle"
        private set

    fun attachBaseContext(app: Application) {
        MultiDex.install(app)
        Helper.install(app)
        isContextAttached = true
    }

    fun getIntentAction(): String {
        return SDKUtils.getUsbAction()
    }

    fun isRegistered(): Boolean {
        return RegistrationHandler.isRegistered()
    }

    fun setActivationCallback(callback: (Boolean, String?) -> Unit) {
        RegistrationHandler.setActivationCallback(callback)
    }

    fun setBindingCallback(callback: (Boolean, String?) -> Unit) {
        RegistrationHandler.setBindingCallback(callback)
    }

    fun setProductChangeCallback(callback: (Boolean) -> Unit) {
        RegistrationHandler.setProductChangeCallback(callback)
    }

    fun startRegistration(context: Context, callback: (Boolean, String?) -> Unit) {
        RegistrationHandler.setSuccessCallback(callback)
        RegistrationHandler.initialize(context)
    }

    fun destroy(context: Context) {
        // TODO: unload everything
        RegistrationHandler.destroy()
    }

    fun getAircraftModel(): String {
        return SDKUtils.getAircraftInstance()?.model?.displayName ?: "No Aircraft Detected"
    }
}
