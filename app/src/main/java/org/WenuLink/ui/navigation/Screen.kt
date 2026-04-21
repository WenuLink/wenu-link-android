package org.WenuLink.ui.navigation

sealed class Screen(val route: String) {
    object Main : Screen("main")
    object About : Screen("about")

    object ConfigMenu : Screen("config_menu")
    object ConfigIp : Screen("config_ip")
    object ConfigDji : Screen("config_dji")
    object ConfigTheme : Screen("config_theme")
}
