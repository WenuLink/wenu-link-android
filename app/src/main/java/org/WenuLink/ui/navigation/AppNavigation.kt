package org.WenuLink.ui.navigation

import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.WenuLink.views.HomeViewModel
import org.WenuLink.views.ServicesViewModel
import org.WenuLink.views.SettingsViewModel
import org.WenuLink.ui.screens.about.AboutScreen
import org.WenuLink.ui.screens.config.*
import org.WenuLink.ui.screens.main.DashboardUiState
import org.WenuLink.ui.screens.main.MainScreen

@Composable
fun AppNavigation(
    homeViewModel: HomeViewModel,
    servicesViewModel: ServicesViewModel,
    settingsViewModel: SettingsViewModel,
    logMessages: List<String>,
    addLog: (String) -> Unit
) {
    val navController = rememberNavController()

    val workflowStatus by homeViewModel.workflowStatus.observeAsState("Idle")
    val isPermissionsGranted by homeViewModel.isPermissionsGranted.observeAsState(false)
    val isRegistered by homeViewModel.isRegistered.observeAsState(false)
    val sdkStatus by homeViewModel.sdkStatus.observeAsState("")

    val canRunService = sdkStatus.contains("Connected")

    val isServiceRunning by servicesViewModel.isServiceRunning.collectAsState()
    val isDataFlowing by servicesViewModel.isDataFlowing.collectAsState()
    val isMAVLinkRunning by servicesViewModel.isMAVLinkRunning.collectAsState()
    val isWebRTCRunning by servicesViewModel.isWebRTCRunning.collectAsState()
    val telemetry by servicesViewModel.telemetryData.observeAsState()

    val telemetrySummary = if (telemetry != null)
        "R:${"%.1f".format(telemetry!!.roll)} P:${"%.1f".format(telemetry!!.pitch)} Y:${"%.1f".format(telemetry!!.yaw)}"
    else "No Telemetry Data"

    val uiState = DashboardUiState(
        workflowStatus = workflowStatus,
        isPermissionsGranted = isPermissionsGranted,
        isSDKRegistered = isRegistered,
        canRunService = canRunService,
        isServiceRunning = isServiceRunning,
        isDataFlowing = isDataFlowing,
        isMAVLinkRunning = isMAVLinkRunning,
        isWebRTCRunning = isWebRTCRunning,
        telemetrySummary = telemetrySummary,
        recentLogs = logMessages
    )

    NavHost(
        navController = navController,
        startDestination = Screen.Main.route,

        enterTransition = {
            slideInHorizontally(
                initialOffsetX = { fullWidth -> fullWidth },
                animationSpec = tween(200)
            )
        },
        exitTransition = {
            slideOutHorizontally(
                targetOffsetX = { fullWidth -> -fullWidth },
                animationSpec = tween(200)
            )
        },
        popEnterTransition = {
            slideInHorizontally(
                initialOffsetX = { fullWidth -> -fullWidth },
                animationSpec = tween(200)
            )
        },
        popExitTransition = {
            slideOutHorizontally(
                targetOffsetX = { fullWidth -> fullWidth },
                animationSpec = tween(200)
            )
        }
    ) {

        composable(Screen.Main.route) {
            MainScreen(
                uiState = uiState,
                onServiceToggle = { servicesViewModel.runService(!isServiceRunning) },
                onMavlinkToggle = { servicesViewModel.runMAVLink(!isMAVLinkRunning) },
                onWebRTCToggle = { servicesViewModel.runWebRTC(!isWebRTCRunning) },
                onNavigateToConfig = { navController.navigate(Screen.ConfigMenu.route) },
                onNavigateToAbout = { navController.navigate(Screen.About.route) }
            )
        }

        composable(Screen.About.route) {
            AboutScreen(navController)
        }
        composable(Screen.ConfigMenu.route) {
            MenuScreen(navController)
        }
        composable(Screen.ConfigIp.route) {
            AddressScreen(navController, settingsViewModel, isServiceRunning)
        }
        composable(Screen.ConfigDji.route) {
            KeyScreen(navController)
        }
        composable(Screen.ConfigTheme.route) {
            ThemeScreen(navController, settingsViewModel)
        }
    }
}