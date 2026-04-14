/*
 * based on https://github.com/dji-sdk/Mobile-SDK-Android/blob/master/Sample%20Code/app/src/main/java/com/dji/sdk/sample/internal/utils/ModuleVerificationUtil.java
 */
package org.WenuLink.sdk

import dji.common.error.DJIError
import dji.common.flightcontroller.GPSSignalLevel
import dji.common.util.CommonCallbacks
import dji.sdk.base.BaseComponent
import io.getstream.log.taggedLogger
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

object SDKUtils {
    private val logger by taggedLogger(SDKUtils::class.java.simpleName)

    fun createCompletionCallback(
        onResult: (String?) -> Unit
    ): CommonCallbacks.CompletionCallback<DJIError> =
        CommonCallbacks.CompletionCallback<DJIError> { error ->
            if (error != null) {
                logger.e { "CompletionCallback onFailure $error" }
            }
            onResult(error?.description)
        }

    fun createCompletionCallback(
        onResult: (String, Boolean) -> Unit
    ): CommonCallbacks.CompletionCallbackWith<String> =
        object : CommonCallbacks.CompletionCallbackWith<String> {
            override fun onSuccess(value: String) = onResult(value, true)

            override fun onFailure(p0: DJIError?) =
                onResult(p0?.description ?: "Unknown error", false)
        }

    suspend fun retrieveFirmwareVersion(componentInstance: BaseComponent): String? =
        suspendCancellableCoroutine { cont ->
            componentInstance.getFirmwareVersion(
                createCompletionCallback { firmwareVersion, _ ->
                    cont.resume(firmwareVersion)
                }
            )
        }

    suspend fun retrieveSerialNumber(componentInstance: BaseComponent): String? =
        suspendCancellableCoroutine { cont ->
            componentInstance.getSerialNumber(
                createCompletionCallback { serialNumber, _ ->
                    cont.resume(serialNumber)
                }
            )
        }
}
