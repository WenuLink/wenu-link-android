package org.WenuLink.ui.screens.main

import org.WenuLink.mavlink.BridgeHealth

data class DashboardUiState(
    val workflowStatus: String = "Idle",
    val isPermissionsGranted: Boolean = false,
    val isSDKRegistered: Boolean = false,
    val isAircraftPresent: Boolean = false,
    val isSimulationReady: Boolean = false,
    val canRunService: Boolean = false,
    val isUsingSimulation: Boolean = false,
    val isServiceRunning: Boolean = false,
    val isMAVLinkRunning: Boolean = false,
    val isWebRTCRunning: Boolean = false,
    val bridgeHealth: BridgeHealth = BridgeHealth.idle,
    // TODO: Logging feature
    val recentLogs: List<String> = listOf("TODO: Logging feature...")
)
