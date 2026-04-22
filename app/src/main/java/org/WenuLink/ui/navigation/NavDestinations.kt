package org.WenuLink.ui.navigation

sealed class Screen(val route: String) {
    object Main : Screen("main")
    object About : Screen("about")

    object ConfigMenu : Screen("config_menu")
    object ConfigMAVLink : Screen("config_mavlink")
    object ConfigWebRTC : Screen("config_webrtc")
    object ConfigTheme : Screen("config_theme")
}

sealed class AddressTarget {
    object MAVLink : AddressTarget()
    object WebRTC : AddressTarget()
}
