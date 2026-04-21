package org.WenuLink

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.WenuLink.ui.navigation.AppNavigation
import org.WenuLink.ui.theme.WenuLinkTheme
import org.WenuLink.views.ServicesViewModel
import org.WenuLink.views.SettingsViewModel

class MainActivity : ComponentActivity() {
    companion object {
        fun getIntent(context: Context): Intent = Intent(context, MainActivity::class.java).apply {
            action = WenuLinkApp.apiIntentAction
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
    }

    private lateinit var thisApp: WenuLinkApp

    private val servicesViewModel: ServicesViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()

    private fun checkAndRequestPermissions() {
        thisApp.updateWorkflow("Checking permissions")

        if (thisApp.missingPermissions.isEmpty()) {
            thisApp.onPermissionsGranted()
            return
        }

        thisApp.updateWorkflow("Waiting for pending permissions")
        val requestPermissionLauncher =
            registerForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { permissionsMap ->
                if (permissionsMap.all { it.value }) {
                    thisApp.onPermissionsGranted()
                } else {
                    thisApp.onPermissionsDenied()
                }
            }
        requestPermissionLauncher.launch(thisApp.missingPermissions.toTypedArray())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        thisApp = (application as WenuLinkApp)

        checkAndRequestPermissions()
        enableEdgeToEdge()

        setContent {
            val context = LocalContext.current

            val initialTheme = remember { WenuLinkPreferences.getThemeMode(context) }
            val themeMode by WenuLinkPreferences.themeFlow.collectAsState(initial = initialTheme)

            val darkTheme = when (themeMode) {
                1 -> false
                2 -> true
                else -> isSystemInDarkTheme()
            }

            WenuLinkTheme(darkTheme = darkTheme) {
                var logMessages by remember {
                    mutableStateOf(listOf("Waiting System Initialization..."))
                }

                val addLog: (String) -> Unit = { message ->
                    val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                    val formattedMessage = "[$time] $message"

                    logMessages = buildList {
                        add(formattedMessage)
                        addAll(logMessages.take(49))
                    }
                }

                AppNavigation(
                    app = thisApp,
                    servicesViewModel = servicesViewModel,
                    settingsViewModel = settingsViewModel,
                    logMessages = logMessages,
                    addLog = addLog
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Deinitialize sdk only when no service is running
        if (!thisApp.isAircraftBoot.value) {
            thisApp.apiDestroy()
        }
    }
}
