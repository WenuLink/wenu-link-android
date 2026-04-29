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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.WenuLink.adapters.AsyncUtils
import org.WenuLink.adapters.WenuLinkHandler
import org.WenuLink.adapters.WenuLinkService
import org.WenuLink.commands.CommandResult
import org.WenuLink.commands.UnitResult
import org.WenuLink.sdk.APIManager

class WenuLinkApp : Application() {
    companion object {
        val apiIntentAction: String get() = APIManager.getIntentAction()
    }
    private val logger by taggedLogger(WenuLinkApp::class.java.simpleName)
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var initJob: Job? = null
    private var connectJob: Job? = null
    private lateinit var wenuLinkHandler: WenuLinkHandler
    private var isContextAttached = false
    var wenuLinkService: WenuLinkService? = null

    // Basic status reporting
    val isPermissionsGranted = MutableStateFlow(false)
    val workflowStatus = MutableStateFlow("Idle")
    val sdkStatus = MutableStateFlow("Idle")

    // SDK state handling
    val isRegistered = MutableStateFlow(false)
    val bindingString = MutableStateFlow("Idle")
    val activationString = MutableStateFlow("Idle")
    val isAircraftPresent = MutableStateFlow(false)
    val isSimulationReady = MutableStateFlow(false)
    val isAircraftBoot = MutableStateFlow(false)
    val isUsingSimulation = MutableStateFlow(false)
    val isServiceUp = MutableStateFlow(false)

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
        logger.i { "SDK status: $text" }
        sdkStatus.value = text
    }

    fun updateWorkflow(text: String) {
        logger.i { "Workflow: $text" }
        workflowStatus.value = text
    }

    fun onPermissionsGranted() {
        logger.i { "All permissions granted" }
        updatePermission(true)
        apiStart()
    }

    fun onPermissionsDenied() {
        logger.w { "Some permissions denied" }
        updatePermission(false)
        updateWorkflow("Missing permission(s), please restart the app.")
    }

    override fun attachBaseContext(paramContext: Context?) {
        super.attachBaseContext(paramContext)
        // Attach logger first
        AndroidStreamLogger.installOnDebuggableApp(this)
        // Attach SDK
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
    }

    fun launchWenulinkService() {
        if (wenuLinkService != null) return

        if (!wenuLinkHandler.isAircraftPowerOn) {
            logger.w { "Unable to start, Aircraft's not ready." }
            return
        }

        val startFunction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            this::startForegroundService
        } else {
            this::startService
        }
        startFunction(Intent(this, WenuLinkService::class.java))
    }

    suspend fun stopWenulinkService() {
        if (wenuLinkService == null) return

        wenuLinkService?.terminate()
        stopService(Intent(this, WenuLinkService::class.java))
    }

    private fun onRegistration(result: UnitResult) {
        if (result is CommandResult.Failure) {
            isRegistered.value = false
            updateStatus(result.reason)
            updateWorkflow("Error during SDK registration")
            return
        }
        isRegistered.value = true
        updateStatus("Registered")
        updateWorkflow("Waiting for Aircraft")
    }

    private fun initHandler() {
        if (initJob != null) {
            logger.w { "Already initializing handler" }
            return
        }

        // Launches it
        initJob = appScope.launch {
            waitComponentsAndStartHandler()
        }

        // wait for termination to clear
        appScope.launch {
            initJob?.join()
            initJob = null
            updateWorkflow("WenuLink with ${wenuLinkHandler.aircraftModel}")
        }
    }

    private suspend fun waitComponentsAndStartHandler() {
        updateWorkflow("Waiting for components")
        val allComponentsPresent =
            AsyncUtils.waitTimeout(100L, 30_000L, APIManager::areComponentsPresent)

        if (!allComponentsPresent) {
            updateWorkflow("No components found, try again...")
            return
        }
        // check if simReady
        isSimulationReady.value = APIManager.isSimulationAvailable

        if (::wenuLinkHandler.isInitialized) return

        updateWorkflow("Initializing handler")
        wenuLinkHandler = WenuLinkHandler.getInstance()
        wenuLinkHandler.registerScope(appScope)
    }

    fun connectAircraft(useSimulation: Boolean = false) {
        if (connectJob?.isActive == true) return

        if (!::wenuLinkHandler.isInitialized) return

        connectJob = appScope.launch {
            wenuLinkHandler.enableSimulation(useSimulation)
            updateWorkflow("Connecting to ${wenuLinkHandler.aircraftModel}")
            // Wait Aircraft to boot
            val bootResult = wenuLinkHandler.loadComponents(appScope)
            if (bootResult.hasError) {
                updateWorkflow("Boot error: ${bootResult.errorReason}")
            } else {
                isAircraftBoot.value = true
                isUsingSimulation.value = useSimulation
                updateWorkflow("Connected and ready for services")
            }
        }
    }

    fun disconnectAircraft() {
        if (connectJob?.isActive == true) return

        if (!::wenuLinkHandler.isInitialized) return

        appScope.launch {
            val shutdownResult = wenuLinkHandler.unloadComponents(false)
            if (shutdownResult.hasError) {
                updateWorkflow("Shutdown error: ${shutdownResult.errorReason}")
            } else {
                isAircraftBoot.value = false
                isUsingSimulation.value = false
            }
            wenuLinkHandler.enableSimulation(false)
        }
    }

    private fun onProductConnected(connected: Boolean) {
        logger.d { "onProductConnected $connected" }
        isAircraftPresent.value = connected
        if (connected) {
            updateStatus("Aircraft present")
            initHandler()
        } else {
            updateStatus("No aircraft present")
            isSimulationReady.value = false
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
        if (::wenuLinkHandler.isInitialized) wenuLinkHandler.unload()
        appScope.cancel()
        APIManager.destroy()
    }
}
