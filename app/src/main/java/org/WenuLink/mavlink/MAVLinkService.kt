package org.WenuLink.mavlink

import io.getstream.log.taggedLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.WenuLink.controllers.MAVLinkController
import kotlin.getValue

class MAVLinkService {
    companion object {
        var isEnabled: Boolean = true
            private set
    }
    private val logger by taggedLogger("MAVLinkService")

    private var client: MAVLinkClient? = null
    private lateinit var controller: MAVLinkController
    private var endpointAddress = "192.168.1.220:14550"
    private var mavlinkScope: CoroutineScope? = null
    private var listeningJob: Job? = null
    private var sendingJob: Job? = null
    var waitingTime: Long = 100L // Called ever 100ms...
        private set
    var isReady: Boolean = false
        private set

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    fun runProcess(isRunning: Boolean) {
        _isRunning.value = isRunning
    }

    fun updateServerAddress(serverAddress: String) {
        endpointAddress = serverAddress
    }

    fun hasStationConnected(): Boolean {
        if (!::controller.isInitialized) return false
        return controller.isStationConnected()
    }

    fun hasTelemetryRunning(): Boolean {
        if (!::controller.isInitialized) return false
        return controller.isTelemetryRunning()
    }

    fun clientExists(): Boolean = client != null

    fun clientIsRunning(): Boolean {
        val hasJob = listeningJob?.isActive ?: false || sendingJob?.isActive ?: false
        return clientExists() && hasJob
    }

    fun clientCanStart(): Boolean = clientExists() && !clientIsRunning()

    fun isServiceRunning(): Boolean = hasTelemetryRunning() || clientIsRunning()

    fun createClient(serviceScope: CoroutineScope) {
        logger.d { "Creating MAVLinkClient for udp://$endpointAddress." }

        if (client != null) return

        val targetIp = endpointAddress.split(":")[0]
        val targetPort = endpointAddress.split(":")[1].toInt()

        client = MAVLinkClient(targetIp, targetPort)
        controller = MAVLinkController(client!!, serviceScope)

        isRunning.distinctUntilChangedBy { it }
            .onEach {
                logger.d { "Requesting to ${if (it) "launch" else "stop"} MAVLinkService." }
                if (it) launchService()
                else stopService()
            }
            .launchIn(serviceScope)
    }

    fun destroyClient() {
        logger.d { "MAVLinkClient destroyed." }
        if (client == null) return
        client?.closeSocket()
        client = null
    }

    suspend fun launchService() {
        if (!clientCanStart()) {
            logger.i { "MAVLinkClient is not ready, possibly is enabled or already running." }
            return
        }
        logger.i { "MAVLinkClient created for udp://$endpointAddress." }

        if (!controller.launchAndWaitTelemetry(5000L)) {
            logger.i { "Telemetry was not launch." }
            return
        }
        logger.i { "Telemetry is broadcasting data." }

        // Input and Output jobs in a IO scope
        mavlinkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        listeningJob = mavlinkScope!!.launch { controller.launchListening() }
        sendingJob = mavlinkScope!!.launch { controller.launchSending(waitingTime) }

        logger.d { "MAVLink (ready?=$isReady) (GCS?=${hasStationConnected()}) (listening?=${listeningJob?.isActive}) (sending=${sendingJob?.isActive})" }

        // Wait for station's heartbeat and shutdown if no received after a while
        val currentStationConnected = controller.waitGroundStation(5000L)
        if (!currentStationConnected) return stopService()

        logger.d { "MAVLink (ready?=$isReady) (GCS?=${hasStationConnected()}) (listening?=${listeningJob?.isActive}) (sending=${sendingJob?.isActive})" }

        mavlinkScope!!.launch {
            isReady = controller.waitSystemReady(60000L)
            if (isReady) controller.notifySystemReady()
        }

        logger.d { "MAVLink (ready?=$isReady) (GCS?=${hasStationConnected()}) (listening?=${listeningJob?.isActive}) (sending=${sendingJob?.isActive})" }

        controller.waitBoot()
    }

    suspend fun stopService() {
        if (mavlinkScope != null) {
            // Wait for stop client
            mavlinkScope!!.launch {
                controller.shutdown()
                sendingJob?.join()
                listeningJob?.join()
            }.join()
            mavlinkScope?.cancel()
            mavlinkScope = null
        }
        isReady = false
        logger.d { "MAVLink (ready?=$isReady) (GCS?=${hasStationConnected()}) (listening?=${listeningJob?.isActive}) (sending=${sendingJob?.isActive})" }
        sendingJob = null
        listeningJob = null
        controller.waitTerminateTelemetry(5000L)
    }

}
