/*
 * based on https://github.com/dji-sdk/Mobile-SDK-Android/blob/master/Sample%20Code/app/src/main/java/com/dji/sdk/sample/internal/utils/ModuleVerificationUtil.java
 */
package org.WenuLink.sdk

import dji.common.error.DJIError
import dji.common.flightcontroller.GPSSignalLevel
import dji.common.util.CommonCallbacks
import io.getstream.log.taggedLogger

object SDKUtils {
    private val logger by taggedLogger(SDKUtils::class.java.simpleName)

    fun gpsSignalLevelFlags(inputLevel: GPSSignalLevel): List<Boolean> =
        GPSSignalLevel.entries.map { it == inputLevel }

    fun createCompletionCallback(
        onResult: (String?) -> Unit
    ): CommonCallbacks.CompletionCallback<DJIError> =
        CommonCallbacks.CompletionCallback<DJIError> { error ->
            if (error != null) {
                logger.e { "CompletionCallback onFailure $error" }
            }
            onResult(error?.description)
        }
}
