package org.WenuLink.adapters.mission

sealed class MissionAction {
    class Delay(val seconds: Int) : MissionAction()
    class Rotate(val degrees: Int) : MissionAction()
    class GimbalPitch(val pitch: Int) : MissionAction()
    object TakePhoto : MissionAction()
    object StartRecord : MissionAction()
    object StopRecord : MissionAction()
}

// import org.WenuLink.adapters.WenuLinkAction
// sealed class NavigationAction : WenuLinkAction {
//
//    data class Delay(val seconds: Int) : NavigationAction()
//
//    data class Rotate(val degrees: Int) : NavigationAction()
// }
