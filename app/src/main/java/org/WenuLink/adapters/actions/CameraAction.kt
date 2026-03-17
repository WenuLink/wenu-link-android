package org.WenuLink.adapters.actions

import kotlinx.coroutines.CompletableDeferred

sealed class CameraAction(open val cameraIdx: Int, override val onResult: (String?) -> Unit) :
    WenuLinkAction(onResult) {

    data class SetMode(
        override val cameraIdx: Int,
        val mode: Int,
        override val onResult: (String?) -> Unit
    ) : CameraAction(cameraIdx, onResult)

    data class TakePhoto(override val cameraIdx: Int, override val onResult: (String?) -> Unit) :
        CameraAction(cameraIdx, onResult)

    data class StartRecord(override val cameraIdx: Int, override val onResult: (String?) -> Unit) :
        CameraAction(cameraIdx, onResult)

    data class StopRecord(override val cameraIdx: Int, override val onResult: (String?) -> Unit) :
        CameraAction(cameraIdx, onResult)

    data class GimbalPitch(
        override val cameraIdx: Int,
        val pitch: Int,
        override val onResult: (String?) -> Unit
    ) : CameraAction(cameraIdx, onResult)
}
