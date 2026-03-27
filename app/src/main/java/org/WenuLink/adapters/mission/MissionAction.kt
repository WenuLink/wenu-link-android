package org.WenuLink.adapters.mission

sealed class MissionAction {
    class Delay(val seconds: Int) : MissionAction()
    class Rotate(val degrees: Int) : MissionAction()
    class GimbalPitch(val pitch: Int) : MissionAction()
    object TakePhoto : MissionAction()
    object StartRecord : MissionAction()
    object StopRecord : MissionAction()
}
