package org.WenuLink

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.core.content.ContextCompat
import io.getstream.log.taggedLogger
import org.WenuLink.sdk.SDKManager
import org.WenuLink.ui.navigation.AppNavigation
import org.WenuLink.ui.theme.WenuLinkTheme
import org.WenuLink.ui.utils.PrefsManager
import org.WenuLink.views.HomeViewModel
import org.WenuLink.views.ServicesViewModel
import org.WenuLink.views.SettingsViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    companion object {
        fun getIntent(context: Context): Intent = Intent(context, MainActivity::class.java).apply {
            action = SDKManager.getIntentAction()
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
    }

    private val logger by taggedLogger(MainActivity::class.java.simpleName)

    private val homeViewModel: HomeViewModel by viewModels()
    private val servicesViewModel: ServicesViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()

    private fun checkAndRequestPermissions() {
        homeViewModel.updateWorkflow("Checking permissions")

        var permissionsList = arrayOf(
            Manifest.permission.VIBRATE,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.WAKE_LOCK,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.READ_PHONE_STATE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            permissionsList += arrayOf(Manifest.permission.FOREGROUND_SERVICE)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsList += arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingPermissions = permissionsList.filter {
            ContextCompat.checkSelfPermission(applicationContext, it) !=
                PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            homeViewModel.updateWorkflow("Waiting for pending permissions")
            val requestPermissionLauncher =
                registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissionsMap ->
                    if (permissionsMap.all { it.value }) {
                        onPermissionsGranted()
                    } else {
                        onPermissionsDenied()
                    }
                }
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        } else {
            onPermissionsGranted()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAndRequestPermissions()
        enableEdgeToEdge()

        setContent {
            val context = LocalContext.current

            val initialTheme = remember { PrefsManager.getThemeMode(context) }
            val themeMode by PrefsManager.themeFlow.collectAsState(initial = initialTheme)

            val darkTheme = when (themeMode) {
                1 -> false
                2 -> true
                else -> isSystemInDarkTheme()
            }

            WenuLinkTheme(darkTheme = darkTheme) {
                var logMessages by remember { mutableStateOf(listOf("Waiting System Initialization...")) }

                val addLog: (String) -> Unit = { message ->
                    val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                    val formattedMessage = "[$time] $message"

                    logMessages = buildList {
                        add(formattedMessage)
                        addAll(logMessages.take(49))
                    }
                }

                AppNavigation(
                    homeViewModel = homeViewModel,
                    servicesViewModel = servicesViewModel,
                    settingsViewModel = settingsViewModel,
                    logMessages = logMessages,
                    addLog = addLog
                )
            }
        }
    }

    private fun onPermissionsGranted() {
        logger.i { "All permissions granted" }
        homeViewModel.updatePermission(true)
        homeViewModel.updateWorkflow("Waiting for SDK")
        homeViewModel.startSDK(applicationContext)
    }

    private fun onPermissionsDenied() {
        logger.e { "Some permissions denied" }
        homeViewModel.updatePermission(false)
        homeViewModel.updateWorkflow("Missing permission(s), please restart the app.")
    }

    override fun onStop() {
        super.onStop()
        // Deinitialize sdk only when no service is running
        if (!servicesViewModel.isServiceRunning.value) {
            homeViewModel.stopSDK(applicationContext)
        }
    }
}