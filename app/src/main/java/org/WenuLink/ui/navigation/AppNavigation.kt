package org.WenuLink.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.WenuLink.WenuLinkApp
import org.WenuLink.ui.screens.about.AboutScreen
import org.WenuLink.ui.screens.config.AddressScreen
import org.WenuLink.ui.screens.config.MenuScreen
import org.WenuLink.ui.screens.config.ThemeScreen
import org.WenuLink.ui.screens.main.DashboardUiState
import org.WenuLink.ui.screens.main.MainScreen
import org.WenuLink.views.ServicesViewModel
import org.WenuLink.views.SettingsViewModel

@Composable
fun AppNavigation(
    app: WenuLinkApp,
    servicesViewModel: ServicesViewModel,
    settingsViewModel: SettingsViewModel,
    logMessages: List<String>,
    addLog: (String) -> Unit
) {
    val navController = rememberNavController()

    val workflowStatus by app.workflowStatus.collectAsState()
    val isPermissionsGranted by app.isPermissionsGranted.collectAsState()
    val isRegistered by app.isRegistered.collectAsState()
    val isAircraftPresent by app.isAircraftPresent.collectAsState()
    val isSimulationReady by app.isSimulationReady.collectAsState()
    val canRunService by app.isAircraftBoot.collectAsState()
    val isServiceRunning by servicesViewModel.isServiceUp.collectAsState()
    val isMAVLinkRunning by servicesViewModel.isMAVLinkRunning.collectAsState()
    val isWebRTCRunning by servicesViewModel.isWebRTCRunning.collectAsState()
    // val telemetry by servicesViewModel.telemetryData.observeAsState()

//    val telemetrySummary = if (telemetry != null) {
//        "R:${"%.1f".format(
//            telemetry!!.roll
//        )} P:${"%.1f".format(telemetry!!.pitch)} Y:${"%.1f".format(telemetry!!.yaw)}"
//    } else {
//        "No Telemetry Data"
//    }

    val uiState = DashboardUiState(
        workflowStatus = workflowStatus,
        isPermissionsGranted = isPermissionsGranted,
        isSDKRegistered = isRegistered,
        isAircraftPresent = isAircraftPresent,
        isSimulationReady = isSimulationReady,
        canRunService = canRunService,
        isServiceRunning = isServiceRunning,
        isMAVLinkRunning = isMAVLinkRunning,
        isWebRTCRunning = isWebRTCRunning,
        // TODO: decide if we want telemetry in the frontend
        // telemetrySummary = telemetrySummary,
        recentLogs = logMessages
    )

    val onConnectAircraft: () -> Unit = { servicesViewModel.loadAircraft(!canRunService) }
    val onUseSimulation: () -> Unit = { servicesViewModel.loadAircraft(true, simEnabled = true) }
    val onServiceToggle: () -> Unit = { servicesViewModel.runService(!isServiceRunning) }
    val onForceStop: () -> Unit = { servicesViewModel.forceStop() }
    val onMavlinkToggle: () -> Unit = { servicesViewModel.runMAVLink(!isMAVLinkRunning) }
    val onWebRTCToggle: () -> Unit = { servicesViewModel.runWebRTC(!isWebRTCRunning) }

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
                onConnectAircraft = onConnectAircraft,
                onUseSimulation = onUseSimulation,
                onServiceToggle = onServiceToggle,
                onForceStop = onForceStop,
                onMavlinkToggle = onMavlinkToggle,
                onWebRTCToggle = onWebRTCToggle,
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
        composable(Screen.ConfigMAVLink.route) {
            AddressScreen(navController, settingsViewModel, isServiceRunning, AddressTarget.MAVLink)
        }
        composable(Screen.ConfigWebRTC.route) {
            AddressScreen(navController, settingsViewModel, isServiceRunning, AddressTarget.WebRTC)
        }
        composable(Screen.ConfigTheme.route) {
            ThemeScreen(navController, settingsViewModel)
        }
    }
}
