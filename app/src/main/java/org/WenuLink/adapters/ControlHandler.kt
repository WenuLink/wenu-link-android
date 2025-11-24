package org.WenuLink.adapters

import io.getstream.log.taggedLogger
import kotlinx.coroutines.CoroutineScope
import org.WenuLink.sdk.FCManager
import kotlin.getValue

class ControlHandler {
    companion object {
        private var mInstance: ControlHandler? = null

        fun getInstance(): ControlHandler {
            if (mInstance == null)
                mInstance = ControlHandler()
            return mInstance!!
        }

    }

    private val logger by taggedLogger("CommandsHandler")

    private lateinit var handlerScope: CoroutineScope //(Dispatchers.Main + Job())

    fun simpleTakeoff() {
        FCManager.simpleTakeoff { error ->
            if (error == null) logger.i { "Take off Success" }
            else logger.e { "Error in simpleTakeoff: $error" }
        }
    }

    fun landing() {
        FCManager.simpleLanding { error ->
            if (error == null) logger.i { "Landing satisfactorily" }
            else logger.e { "Error in landing $error" }
        }
    }

    fun flyTo() {
        TODO("new method")
    }

    // TODO: all movement methods
}
