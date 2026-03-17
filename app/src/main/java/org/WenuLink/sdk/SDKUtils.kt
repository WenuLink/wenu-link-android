/*
 * based on https://github.com/dji-sdk/Mobile-SDK-Android/blob/master/Sample%20Code/app/src/main/java/com/dji/sdk/sample/internal/utils/ModuleVerificationUtil.java
 */
package org.WenuLink.sdk

import dji.common.error.DJIError
import dji.common.flightcontroller.GPSSignalLevel
import dji.common.util.CommonCallbacks
import dji.sdk.base.BaseProduct
import dji.sdk.products.Aircraft
import dji.sdk.realname.AppActivationManager
import dji.sdk.sdkmanager.DJISDKManager
import io.getstream.log.taggedLogger

object SDKUtils {
    private val logger by taggedLogger(SDKUtils::class.java.simpleName)

    fun getUsbAction(): String = DJISDKManager.USB_ACCESSORY_ATTACHED

    fun getProductInstance(): BaseProduct? = DJISDKManager.getInstance().product

    fun isAircraftConnected(): Boolean =
        getProductInstance() != null && getProductInstance() is Aircraft

    fun getAircraftInstance(): Aircraft? {
        if (!isAircraftConnected()) {
            return null
        }
        return getProductInstance() as Aircraft?
    }

    fun getAppActivationManager(): AppActivationManager? =
        DJISDKManager.getInstance().appActivationManager

    fun getGPSSignalLevelArray(inputLevel: GPSSignalLevel): BooleanArray {
        // Create a boolean array with the same size as the number of enum constants
        val result = BooleanArray(GPSSignalLevel.entries.size)

        // Iterate through the enum constants and set the corresponding index to true if it matches the input level
        for (i in GPSSignalLevel.entries.indices) {
            result[i] = GPSSignalLevel.entries[i] == inputLevel
        }

        return result
    }

    fun createCompletionCallback(
        onResult: (String?) -> Unit
    ): CommonCallbacks.CompletionCallback<DJIError> =
        CommonCallbacks.CompletionCallback<DJIError> { error ->
            if (error == null) {
                onResult(null)
            } else {
                logger.e { "CompletionCallback onFailure $error" }
                onResult(error.description)
            }
        }
}
