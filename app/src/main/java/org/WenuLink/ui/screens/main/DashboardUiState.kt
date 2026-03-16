package org.WenuLink.ui.screens.main

data class DashboardUiState(
    val workflowStatus: String = "Idle",
    val isPermissionsGranted: Boolean = false,
    val isSDKRegistered: Boolean = false,
    val canRunService: Boolean = false,
    val isServiceRunning: Boolean = false,
    val isDataFlowing: Boolean = false,
    val isMAVLinkRunning: Boolean = false,
    val isWebRTCRunning: Boolean = false,
    val telemetrySummary: String = "No Telemetry Data",
    // TODO: Logging feature
    val recentLogs: List<String> = listOf("TODO: Logging feature...")
)