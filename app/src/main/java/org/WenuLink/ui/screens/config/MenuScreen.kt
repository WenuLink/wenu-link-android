package org.WenuLink.ui.screens.config

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import org.WenuLink.ui.navigation.Screen

private data class SettingsItem(
    val title: String,
    val subtitle: String,
    val screen: Screen,
    val icon: ImageVector
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuScreen(navController: NavController) {
    val settingsItems = listOf(
        SettingsItem(
            "MAVLink protocol",
            "MAVLink Protocol Settings",
            Screen.ConfigMAVLink,
            Icons.Default.Wifi
        ),
        SettingsItem(
            "WebRTC streaming",
            "WebRTC Streaming Settings",
            Screen.ConfigWebRTC,
            Icons.Default.CellTower
        ),
        SettingsItem(
            "WenuLink Interface",
            "Theme & Display",
            Screen.ConfigTheme,
            Icons.Default.Palette
        )
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground
                ),
                windowInsets = WindowInsets.safeDrawing
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            items(settingsItems) { (title, subtitle, screen, icon) ->
                ListItem(
                    headlineContent = { Text(title, fontWeight = FontWeight.Medium) },
                    supportingContent = { Text(subtitle) },
                    leadingContent = { Icon(imageVector = icon, contentDescription = null) },
                    trailingContent = {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForwardIos,
                            null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    modifier = Modifier
                        .clickable { navController.navigate(screen.route) },

                    colors = ListItemDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.background,
                        headlineColor = MaterialTheme.colorScheme.onBackground,
                        leadingIconColor = MaterialTheme.colorScheme.primary,
                        supportingColor = MaterialTheme.colorScheme.secondary
                    )
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                )
            }
        }
    }
}
