package org.WenuLink

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

import org.WenuLink.views.HomeViewModel
import org.WenuLink.sdk.SDKManager
import org.WenuLink.ui.theme.WenuLinkTheme
import org.WenuLink.views.ServicesViewModel


class MainActivity : ComponentActivity() {
    companion object {
        fun getIntent(context: Context): Intent {
            return Intent(context, MainActivity::class.java).apply {
                action = SDKManager.getIntentAction()
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        }
    }

    private val TAG: String = MainActivity::class.java.simpleName

    private val homeViewModel: HomeViewModel by viewModels()
    private val servicesViewModel: ServicesViewModel by viewModels()

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
            Manifest.permission.READ_PHONE_STATE,
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            permissionsList += arrayOf(Manifest.permission.FOREGROUND_SERVICE)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsList += arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingPermissions = permissionsList.filter {
            ContextCompat.checkSelfPermission(applicationContext, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            homeViewModel.updateWorkflow("Waiting for pending permissions")
            val requestPermissionLauncher =
                registerForActivityResult(ActivityResultContracts.
                RequestMultiplePermissions()) { permissionsMap ->
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

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        checkAndRequestPermissions()

        enableEdgeToEdge()
        setContent {
            WenuLinkTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(title = { Text("WenuLink status") })
                    }
                ) { innerPadding ->
                    MainScreen(
                        viewModel = homeViewModel,
                        modifier = Modifier
                            .padding(innerPadding)
                            .fillMaxSize()
                    )
                }
            }
        }
    }

    private fun onPermissionsGranted() {
        Log.i(TAG, "All permissions granted")
        homeViewModel.updatePermission(true)
        homeViewModel.updateWorkflow("Waiting for SDK")
        homeViewModel.startSDK(applicationContext)
    }

    private fun onPermissionsDenied() {
        Log.e(TAG, "Some permissions denied")
        homeViewModel.updatePermission(false)
        homeViewModel.updateWorkflow("Missing permission(s), please restart the app.")
    }

    override fun onStop() {
        super.onStop()
        // Deinitialize sdk only when no service is running
        if(!servicesViewModel.isServiceRunning.value){
            homeViewModel.stopSDK(applicationContext)
        }
        // TODO: mostrar aviso para forzar salida
    }

    @Composable
    fun MainScreen(viewModel: HomeViewModel, modifier: Modifier = Modifier) {
        // Basic
        val isPermissionsGranted by viewModel.isPermissionsGranted.observeAsState(false)
        val workflowStatus by viewModel.workflowStatus.observeAsState("Idle")
        val isServiceRunning by servicesViewModel.isServiceRunning.collectAsState(false)
        var isSimulationReady by remember { mutableStateOf(servicesViewModel.isSimulationReady()) }
        var isSimulationActive by remember { mutableStateOf(servicesViewModel.isSimulationActive()) }
        // DJI
        val isSDKOk by viewModel.isRegistered.observeAsState(false)
        val sdkStatus by viewModel.sdkStatus.observeAsState("Idle")
//        val canRunService by viewModel.canRunService.observeAsState(false)
//        val bindingState by viewModel.bindingState.observeAsState("Waiting Binding")
//        val activationState by viewModel.activationState.observeAsState("Waiting Activation")
        // MAVLink
        val telemetry by servicesViewModel.telemetryData.observeAsState()
        val isDataFlowing by servicesViewModel.isDataFlowing.collectAsState(false)
        val isMAVLinkRunning by servicesViewModel.isMAVLinkRunning.collectAsState(false)
        // WebRTC
        val isWebRTCRunning by servicesViewModel.isWebRTCRunning.collectAsState(false)
        // Logs
        var logMessages by remember { mutableStateOf(listOf<String>()) }

        // UI code here using telemetry and status
        Column(
            modifier = modifier.padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("App status:")
            Text(workflowStatus)
            if(isPermissionsGranted) {
                Spacer(Modifier.height(4.dp))
                Text("SDK is registered?: $isSDKOk")
                Spacer(Modifier.height(2.dp))
                Text("DataFlowing is active?: $isDataFlowing")
                Spacer(Modifier.height(2.dp))
                Text("MAVLinkService's up?: $isMAVLinkRunning")
                Spacer(Modifier.height(2.dp))
                Text("WebRTCService's up?: $isWebRTCRunning")
                Spacer(Modifier.height(8.dp))
                Text("SDK status:")
                Text(sdkStatus)
            }

            if(isSDKOk){
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    servicesViewModel.runService(!isServiceRunning)
                }) {
                    Text(if (isServiceRunning) {
                        "Stop Drone Service"
                    } else {
                        "Start Drone Service"
                    })
                }

//                if (isSimulationReady) {
                // TODO: Improve simulation enable/disable UI/UX
                    Button(onClick = {
                        servicesViewModel.enableSimulation(!isSimulationActive)
                        isSimulationActive = !isSimulationActive
                    }) {
                        Text(
                            if (!isSimulationActive) {
                                "Enable simulation"
                            } else {
                                "Disable simulation"
                            }
                        )
                    }
//                }

                if (isServiceRunning) {
                    HorizontalDivider()
                    Button(onClick = {
                        servicesViewModel.runMAVLink(!isMAVLinkRunning)
                    }) {
                        Text(if (isMAVLinkRunning) {
                            "Stop MAVLink"
                        } else {
                            "Start MAVLink"
                        })
                    }
                    Button(onClick = {
                        servicesViewModel.runWebRTC(!isWebRTCRunning)
                    }) {
                        Text(if (isWebRTCRunning) {
                            "Stop WebRTC"
                        } else {
                            "Start WebRTC"
                        })
                    }
                }
            }
            HorizontalDivider()
            Button(
                onClick = {
                    logMessages = logMessages + "Log manual em ${System.currentTimeMillis()}"
                },
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Text("Adicionar Log (Teste)")
            }
            Card(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.padding(8.dp),
                    reverseLayout = true
                ) {
                    items(logMessages) { message ->
                        Text(text = message, modifier = Modifier.padding(4.dp))
                        HorizontalDivider()
                    }
                }
            }

            telemetry?.let {
                Text("Telemetry: R=${it.roll}, P=${it.pitch}, Y=${it.yaw}, Alt=${it.altitude}")
            }
        }
    }
}
