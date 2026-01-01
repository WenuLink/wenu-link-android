package org.WenuLink.adapters

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object Utils {
    fun waitReadiness(
        handlerScope: CoroutineScope,
        delayTime: Long = 100,
        maxTime: Long = 2000,
        invertCondition: Boolean = false,
        isReady: () -> Boolean,
        onResult: (Boolean) -> Unit
    ) {
        handlerScope.launch {
            val startTime = System.currentTimeMillis()

            while (System.currentTimeMillis() - startTime < maxTime) {
                var readyCondition = isReady()
                if (invertCondition) readyCondition = !readyCondition
                if (readyCondition) {
                    onResult(true) // Indicate readiness
                    return@launch // Exit the coroutine
                }
                delay(delayTime) // Wait for the next check
            }
            // If the timeout is reached without readiness
            onResult(false) // Indicate not ready
        }
    }
}