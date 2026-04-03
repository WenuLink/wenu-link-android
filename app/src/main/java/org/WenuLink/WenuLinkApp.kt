package org.WenuLink

import android.Manifest
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.multidex.MultiDex
import com.cySdkyc.clx.Helper
import io.getstream.log.AndroidStreamLogger
import io.getstream.log.taggedLogger
import kotlinx.coroutines.flow.MutableStateFlow
import org.WenuLink.adapters.AsyncUtils
import org.WenuLink.adapters.WenuLinkHandler
import org.WenuLink.adapters.WenuLinkService
import org.WenuLink.sdk.APIManager

class WenuLinkApp : Application() {
    companion object {
        val apiIntentAction: String get() = APIManager.getIntentAction()
    }
    private val logger by taggedLogger(WenuLinkApp::class.java.simpleName)

    val isRegistered = MutableStateFlow(false)
    val bindingString = MutableStateFlow("Idle")
    val activationString = MutableStateFlow("Idle")
    val isAircraftPresent = MutableStateFlow(false)
    val isPermissionsGranted = MutableStateFlow(false)
    val workflowStatus = MutableStateFlow("Idle")
    val sdkStatus = MutableStateFlow("Idle")
    val isSimulationReady = MutableStateFlow(false)
    val isAircraftBoot = MutableStateFlow(false)

    var wenuLinkService: WenuLinkService? = null
    var wenuLinkHandler: WenuLinkHandler? = null
    var isContextAttached: Boolean = false

    private val permissionsList by lazy {
        val permList = mutableListOf(
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
            permList += Manifest.permission.FOREGROUND_SERVICE
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permList += Manifest.permission.POST_NOTIFICATIONS
        }
        return@lazy permList
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == apiIntentAction) {
                logger.i { "USB event detected: ${intent.action}" }
                context.startActivity(MainActivity.getIntent(context))
            }
        }
    }

    val missingPermissions: List<String> get() = permissionsList.filter {
        ContextCompat.checkSelfPermission(applicationContext, it) !=
            PackageManager.PERMISSION_GRANTED
    }.toList()

    private fun updatePermission(granted: Boolean) {
        isPermissionsGranted.value = granted
    }

    private fun updateStatus(text: String) {
        sdkStatus.value = text
    }

    fun updateWorkflow(text: String) {
        workflowStatus.value = text
    }

    fun onPermissionsGranted() {
        logger.i { "All permissions granted" }
        updatePermission(true)
        updateWorkflow("Waiting for SDK")
        apiStart()
    }

    fun onPermissionsDenied() {
        logger.e { "Some permissions denied" }
        updatePermission(false)
        updateWorkflow("Missing permission(s), please restart the app.")
    }

    override fun attachBaseContext(paramContext: Context?) {
        super.attachBaseContext(paramContext)
        MultiDex.install(this)
        Helper.install(this)
        isContextAttached = true
    }

    override fun onCreate() {
        super.onCreate()
        logger.i { "STARTING.." }
        if (!isContextAttached) {
            logger.e { "Fatal error: SDK context not attached!" }
            return
        }
        // Register to listen for USB attach/detach
        val filter = IntentFilter().apply {
            addAction(apiIntentAction) // your SDK’s action
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }

        AndroidStreamLogger.installOnDebuggableApp(this)
    }

    suspend fun launchWenulinkService() {
        if (wenuLinkService != null) return

        val startFunction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            this::startForegroundService
        } else {
            this::startService
        }
        startFunction(Intent(this, WenuLinkService::class.java))

        AsyncUtils.waitReady(500L) { isAircraftBoot.value }

        wenuLinkService?.runServices()
    }

    suspend fun stopWenulinkService() {
        if (wenuLinkService == null) return

        wenuLinkService?.terminate()
        wenuLinkHandler?.enableSimulation(false) // always disable
        stopService(
            Intent(this, WenuLinkService::class.java)
        )
    }

    private fun onRegistration(error: String?) {
        if (error == null) {
            isRegistered.value = true
            updateStatus("Registered")
            updateWorkflow("Waiting for Aircraft")
        } else {
            isRegistered.value = false
            updateStatus(error)
            updateWorkflow("Error during SDK registration")
        }
    }

    private fun onProductConnected(connected: Boolean) {
        logger.d { "onProductConnected $connected" }
        isAircraftPresent.value = connected
        if (connected) {
            updateWorkflow("Initializing handler")
            wenuLinkHandler = WenuLinkHandler.getInstance()
            updateStatus("Connected to ${wenuLinkHandler!!.aircraftModel}.")
            isSimulationReady.value = APIManager.isSimulationAvailable
        }
    }

    private fun onActivation(success: Boolean, error: String?) {
        logger.d { "activationChanged $success, $error" }
        if (error != null) activationString.value = error
    }

    private fun onBinding(success: Boolean, error: String?) {
        logger.d { "bindingChanged $success, $error" }
        if (error != null) bindingString.value = error
    }

    fun apiStart() {
        updateWorkflow("Initializing SDK")
        APIManager.registerCallbacks(
            registrationCallback = ::onRegistration, // Start workflow
            productConnectedCallback = ::onProductConnected, // Enable UI
            activationCallback = ::onActivation,
            bindingCallback = ::onBinding
        )
        APIManager.initialize(this)
    }

    fun apiDestroy() {
        APIManager.destroy()
    }
}
