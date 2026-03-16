package org.WenuLink.ui.screens.main

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    uiState: DashboardUiState,
    onServiceToggle: () -> Unit,
    onMavlinkToggle: () -> Unit,
    onWebRTCToggle: () -> Unit,
    onNavigateToConfig: () -> Unit,
    onNavigateToAbout: () -> Unit,
    modifier: Modifier = Modifier
) {
    val orientation = LocalConfiguration.current.orientation
    val isLandscape = orientation == Configuration.ORIENTATION_LANDSCAPE

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("WenuLink Status") },
                actions = {
                    IconButton(onClick = onNavigateToConfig) { Icon(Icons.Default.Settings, "Config") }
                    IconButton(onClick = onNavigateToAbout) { Icon(Icons.Default.Info, "About") }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.onBackground
                ),
                windowInsets = WindowInsets.safeDrawing
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        if (isLandscape) {
            LandscapeContent(
                modifier = modifier.padding(innerPadding),
                uiState = uiState,
                onServiceToggle = onServiceToggle,
                onMavlinkToggle = onMavlinkToggle,
                onWebRTCToggle = onWebRTCToggle
            )
        } else {
            PortraitContent(
                modifier = modifier.padding(innerPadding),
                uiState = uiState,
                onServiceToggle = onServiceToggle,
                onMavlinkToggle = onMavlinkToggle,
                onWebRTCToggle = onWebRTCToggle
            )
        }
    }
}

@Composable
private fun PortraitContent(
    modifier: Modifier,
    uiState: DashboardUiState,
    onServiceToggle: () -> Unit,
    onMavlinkToggle: () -> Unit,
    onWebRTCToggle: () -> Unit
) {
    Column(
        modifier = modifier.padding(16.dp).fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        StatusSection(uiState)
        Spacer(modifier = Modifier.height(16.dp))

        if (uiState.isSDKRegistered && uiState.canRunService) {
            ActionsSection(
                uiState.isServiceRunning, uiState.isMAVLinkRunning, uiState.isWebRTCRunning,
                onServiceToggle, onMavlinkToggle, onWebRTCToggle
            )
        } else {
            PlaceholderBox()
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(modifier = Modifier.height(8.dp))
        LogSection(uiState.recentLogs)
    }
}

@Composable
private fun LandscapeContent(
    modifier: Modifier,
    uiState: DashboardUiState,
    onServiceToggle: () -> Unit,
    onMavlinkToggle: () -> Unit,
    onWebRTCToggle: () -> Unit
) {
    Row(modifier = modifier.padding(16.dp).fillMaxSize()) {
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight().padding(end = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            StatusSection(uiState)
            Spacer(modifier = Modifier.height(16.dp))
            if (uiState.isSDKRegistered && uiState.canRunService) {
                ActionsSection(
                    uiState.isServiceRunning, uiState.isMAVLinkRunning, uiState.isWebRTCRunning,
                    onServiceToggle, onMavlinkToggle, onWebRTCToggle
                )
            } else {
                PlaceholderBox()
            }
        }

        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            Text("System Logs", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            LogSection(uiState.recentLogs)
        }
    }
}

@Composable
private fun PlaceholderBox() {
    Box(
        modifier = Modifier.fillMaxWidth().height(80.dp),
        contentAlignment = Alignment.Center
    ) {
        Text("Waiting for Aircraft Connection...", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatusSection(uiState: DashboardUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("STATUS:", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(uiState.workflowStatus, style = MaterialTheme.typography.bodyMedium)
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    StatusCheckItem("Permissions", uiState.isPermissionsGranted)
                    StatusCheckItem("SDK Register", uiState.isSDKRegistered)
                    StatusCheckItem("Drone Linked", uiState.canRunService)
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    StatusCheckItem("MAVLink Svc", uiState.isMAVLinkRunning)
                    StatusCheckItem("WebRTC Svc", uiState.isWebRTCRunning)
                    StatusCheckItem("Data Flow", uiState.isDataFlowing)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(uiState.telemetrySummary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(8.dp), fontFamily = FontFamily.Monospace, maxLines = 1)
            }
        }
    }
}

@Composable
private fun StatusCheckItem(label: String, isActive: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Icon(
            imageVector = if (isActive) Icons.Filled.CheckCircle else Icons.Filled.Close,
            contentDescription = null,
            tint = if (isActive) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = if (isActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ActionsSection(
    isServiceRunning: Boolean,
    isMAVLinkRunning: Boolean,
    isWebRTCRunning: Boolean,
    onServiceToggle: () -> Unit,
    onMavlinkToggle: () -> Unit,
    onWebRTCToggle: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Button(
            onClick = onServiceToggle,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = if (isServiceRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        ) {
            Text(if (isServiceRunning) "STOP DRONE SERVICE" else "START DRONE SERVICE")
        }
        if (isServiceRunning) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onMavlinkToggle, modifier = Modifier.weight(1f)) {
                    Text(if (isMAVLinkRunning) "Stop MAVLink" else "Start MAVLink")
                }
                OutlinedButton(onClick = onWebRTCToggle, modifier = Modifier.weight(1f)) {
                    Text(if (isWebRTCRunning) "Stop WebRTC" else "Start WebRTC")
                }
            }
        }
    }
}

@Composable
private fun LogSection(messages: List<String>) {
    Card(
        modifier = Modifier.fillMaxSize(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        LazyColumn(
            modifier = Modifier.padding(8.dp),
            contentPadding = PaddingValues(bottom = 8.dp)
        ) {
            items(messages) { message ->
                Text(message, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 2.dp))
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.surfaceVariant)
            }
        }
    }
}