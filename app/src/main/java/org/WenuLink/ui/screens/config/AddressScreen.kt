package org.WenuLink.ui.screens.config

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import org.WenuLink.views.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddressScreen(
    navController: NavController,
    settingsViewModel: SettingsViewModel,
    isServiceRunning: Boolean
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    // config variable
    val currentMavlinkIp by settingsViewModel.mavlinkIp.collectAsState()
    val currentWebrtcIp by settingsViewModel.webrtcIp.collectAsState()

    // temp variable
    var localMavlinkIp by remember(currentMavlinkIp) { mutableStateOf(currentMavlinkIp) }
    var localWebrtcIp by remember(currentWebrtcIp) { mutableStateOf(currentWebrtcIp) }

    var isEditing by remember { mutableStateOf(false) }

    ConfigScaffold("Connection Settings", navController) {
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
                        text = "Stop Drone Service to edit IP addresses",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        } else {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isEditing) "Editing Mode Active" else "View Only Mode",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        IpFieldItem(
            label = "MAVLink GCS IP Address",
            value = localMavlinkIp,
            isEditing = isEditing,
            placeholder = "e.g. 192.168.1.220",
            onValueChange = { localMavlinkIp = it },
            onCopy = {
                clipboardManager.setText(AnnotatedString(currentMavlinkIp))
                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        IpFieldItem(
            label = "WebRTC Signaling IP Address",
            value = localWebrtcIp,
            isEditing = isEditing,
            placeholder = "e.g. 192.168.1.100",
            onValueChange = { localWebrtcIp = it },
            onCopy = {
                clipboardManager.setText(AnnotatedString(currentWebrtcIp))
                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            }
        )

        Spacer(modifier = Modifier.weight(1f))

        Button(
            enabled = !isServiceRunning,
            onClick = {
                if (isEditing) {
                    settingsViewModel.saveMavlinkIp(localMavlinkIp)
                    settingsViewModel.saveWebrtcIp(localWebrtcIp)
                    isEditing = false
                    Toast.makeText(
                        context,
                        "Settings saved successfully",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    isEditing = true
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isEditing) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
                contentColor = if (isEditing) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                }
            )
        ) {
            Icon(
                imageVector = if (isEditing) Icons.Default.Save else Icons.Default.Edit,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (isEditing) "SAVE CONFIGURATION" else "EDIT CONFIGURATION")
        }
    }
}

@Composable
fun IpFieldItem(
    label: String,
    value: String,
    isEditing: Boolean,
    placeholder: String,
    onValueChange: (String) -> Unit,
    onCopy: () -> Unit
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary
        )
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = if (isEditing) {
                onValueChange
            } else {
                {}
            },
            readOnly = !isEditing,
            placeholder = { Text(placeholder, color = Color.Gray) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            trailingIcon = {
                if (!isEditing) {
                    IconButton(onClick = onCopy) {
                        Icon(
                            Icons.Default.ContentCopy,
                            "Copy",
                            tint = MaterialTheme.colorScheme.outline
                        )
                    }
                } else {
                    Icon(Icons.Default.Edit, "Editing", tint = MaterialTheme.colorScheme.primary)
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = if (isEditing) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                },
                unfocusedContainerColor = if (!isEditing) {
                    MaterialTheme.colorScheme.surfaceVariant.copy(
                        alpha = 0.3f
                    )
                } else {
                    Color.Transparent
                }
            )
        )
    }
}
