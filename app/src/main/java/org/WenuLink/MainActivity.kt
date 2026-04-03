package org.WenuLink

import android.content.Context
import android.content.Intent
import android.os.Bundle
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.getstream.log.taggedLogger
import org.WenuLink.ui.theme.WenuLinkTheme
import org.WenuLink.views.ServicesViewModel

class MainActivity : ComponentActivity() {
    companion object {
        fun getIntent(context: Context): Intent = Intent(context, MainActivity::class.java).apply {
            action = WenuLinkApp.apiIntentAction
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
    }

    private val logger by taggedLogger(MainActivity::class.java.simpleName)
    private lateinit var thisApp: WenuLinkApp

    private val servicesViewModel: ServicesViewModel by viewModels()

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

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        thisApp = (application as WenuLinkApp)

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
                        viewModel = servicesViewModel,
                        modifier = Modifier
                            .padding(innerPadding)
                            .fillMaxSize()
                    )
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Deinitialize sdk only when no service is running
        if (!thisApp.isAircraftBoot.value) {
            thisApp.apiDestroy()
        }
        // TODO: display warning to force exit
    }

    @Composable
    fun MainScreen(viewModel: ServicesViewModel, modifier: Modifier = Modifier) {
        // Basic
        val isPermissionsGranted by viewModel.isPermissionsGranted.collectAsState(false)
        val workflowStatus by viewModel.workflowStatus.collectAsState("Idle")
        val sdkStatus by viewModel.sdkStatus.collectAsState("Idle")

        // DJI
        val isSDKOk by viewModel.isRegistered.collectAsState(false)
//        val bindingState by viewModel.bindingState.collectAsState("Waiting Binding")
//        val activationState by viewModel.activationState.collectAsState("Waiting Activation")
        val isAircraftPresent by viewModel.isAircraftPresent.collectAsState(false)
        val isSimulationReady by viewModel.isSimReady.collectAsState(false)
        val isAircraftUp by viewModel.isAircraftBoot.collectAsState(false)
        // services
        val isDataFlowing by viewModel.telemetryStateFlow.collectAsState(false)
        val isMAVLinkRunning by viewModel.isMAVLinkRunning.collectAsState(false)
        val isWebRTCRunning by viewModel.isWebRTCRunning.collectAsState(false)
        // Logs
        var logMessages by remember { mutableStateOf(listOf<String>()) }

        fun buttonLabel(isRunning: Boolean) = if (isRunning) "Stop" else "Start"

        // UI code here using telemetry and status
        Column(
            modifier = modifier.padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("App status:")
            Text(workflowStatus)
            if (isPermissionsGranted) {
                Spacer(Modifier.height(4.dp))
                Text("SDK registered?: $isSDKOk")
                Spacer(Modifier.height(2.dp))
                Text("DataFlow active?: $isDataFlowing")
                Spacer(Modifier.height(2.dp))
                Text("Aircraft boot?: $isAircraftUp")
                Spacer(Modifier.height(2.dp))
                Text("MAVLinkService up?: $isMAVLinkRunning")
                Spacer(Modifier.height(2.dp))
                Text("WebRTCService up?: $isWebRTCRunning")
                Spacer(Modifier.height(8.dp))
                Text("SDK status:")
                Text(sdkStatus)
            }

            if (isSDKOk && isAircraftPresent) {
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    servicesViewModel.runService(!isAircraftUp, false)
                }) {
                    Text("${buttonLabel(isAircraftUp)} WenuLink Service")
                }

                if (isSimulationReady && !isAircraftUp) {
                    Button(onClick = {
                        servicesViewModel.runService(run = true, simEnabled = true)
                    }) {
                        Text("Start SIM WenuLink Service")
                    }
                }

                if (isAircraftUp) {
                    HorizontalDivider()

                    Button(onClick = {
                        servicesViewModel.forceStop()
                    }) {
                        Text("FORCE STOP")
                    }

                    HorizontalDivider()

                    Button(onClick = {
                        servicesViewModel.runMAVLink(!isMAVLinkRunning)
                    }) {
                        Text("${buttonLabel(isMAVLinkRunning)} MAVLink")
                    }

                    Button(onClick = {
                        servicesViewModel.runWebRTC(!isWebRTCRunning)
                    }) {
                        Text("${buttonLabel(isWebRTCRunning)} WebRTC")
                    }
                }
            }

            HorizontalDivider()

            Button(
                onClick = {
                    logMessages = logMessages + "Manual Log at ${System.currentTimeMillis()}"
                },
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Text("Add Test Log")
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
        }
    }
}
