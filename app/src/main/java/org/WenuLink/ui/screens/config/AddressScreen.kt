package org.WenuLink.ui.screens.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import org.WenuLink.ui.navigation.AddressTarget
import org.WenuLink.views.SettingsViewModel

private data class AddressScreenConfig(
    val title: String,
    val ipLabel: String,
    val ipPlaceholder: String,
    val portPlaceholder: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddressScreen(
    navController: NavController,
    settingsViewModel: SettingsViewModel,
    isServiceRunning: Boolean,
    target: AddressTarget
) {
    val focusManager = LocalFocusManager.current

    val currentIp by when (target) {
        is AddressTarget.MAVLink -> settingsViewModel.mavlinkIp.collectAsState()
        is AddressTarget.WebRTC -> settingsViewModel.webrtcIp.collectAsState()
    }
    val currentPort by when (target) {
        is AddressTarget.MAVLink -> settingsViewModel.mavlinkPort.collectAsState()
        is AddressTarget.WebRTC -> settingsViewModel.webrtcPort.collectAsState()
    }
    val saveIp: (String) -> Unit = when (target) {
        is AddressTarget.MAVLink -> settingsViewModel::saveMavlinkIp
        is AddressTarget.WebRTC -> settingsViewModel::saveWebrtcIp
    }
    val savePort: (Int) -> Unit = when (target) {
        is AddressTarget.MAVLink -> settingsViewModel::saveMavlinkPort
        is AddressTarget.WebRTC -> settingsViewModel::saveWebrtcPort
    }

    val config = when (target) {
        is AddressTarget.MAVLink -> AddressScreenConfig(
            title = "MAVLink Protocol",
            ipLabel = "MAVLink GCS Address",
            ipPlaceholder = "e.g. 192.168.1.220",
            portPlaceholder = "14550"
        )

        is AddressTarget.WebRTC -> AddressScreenConfig(
            title = "WebRTC Streaming",
            ipLabel = "WebRTC Signaling Address",
            ipPlaceholder = "e.g. 192.168.1.100",
            portPlaceholder = "8090"
        )
    }

    var ipInput by remember(currentIp) { mutableStateOf(currentIp) }
    var portInput by remember(currentPort) { mutableStateOf(currentPort.toString()) }

    val ipError = validateIp(ipInput)
    val portError = validatePort(portInput)

    ConfigScaffold(config.title, navController) {
        if (isServiceRunning) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Stop Drone Service to edit address",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top
        ) {
            OutlinedTextField(
                value = ipInput,
                onValueChange = { input ->
                    ipInput = input.filter { !it.isWhitespace() }
                },
                label = { Text(config.ipLabel) },
                placeholder = { Text(config.ipPlaceholder) },
                singleLine = true,
                isError = ipError != null,
                supportingText = { Text(ipError.orEmpty()) },
                enabled = !isServiceRunning,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                    autoCorrectEnabled = false
                ),
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = FontFamily.Monospace
                ),
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { focus ->
                        if (!focus.isFocused &&
                            validateIp(ipInput) == null &&
                            ipInput != currentIp
                        ) {
                            saveIp(ipInput)
                        }
                    }
            )
            OutlinedTextField(
                value = portInput,
                onValueChange = { input ->
                    if (input.length <= 5 && input.all(Char::isDigit)) {
                        portInput = input
                    }
                },
                label = { Text("Port") },
                placeholder = { Text(config.portPlaceholder) },
                singleLine = true,
                isError = portError != null,
                supportingText = { Text(portError.orEmpty()) },
                enabled = !isServiceRunning,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = { focusManager.clearFocus() }
                ),
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = FontFamily.Monospace
                ),
                modifier = Modifier
                    .width(120.dp)
                    .onFocusChanged { focus ->
                        if (!focus.isFocused) {
                            val parsed = portInput.toIntOrNull()
                            if (parsed != null &&
                                parsed in 1..65535 &&
                                parsed != currentPort
                            ) {
                                savePort(parsed)
                            }
                        }
                    }
            )
        }
    }
}

private fun validateIp(input: String): String? = when {
    input.isEmpty() -> "Address required"
    input.contains(':') -> "Remove the colon, port is separate"
    input.contains('/') -> "Don't include the scheme prefix"
    else -> null
}

private fun validatePort(input: String): String? {
    if (input.isEmpty()) return "Port required"
    val port = input.toIntOrNull() ?: return "Invalid number"
    return if (port !in 1..65535) "Port must be 1–65535" else null
}
