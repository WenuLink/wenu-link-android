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
import kotlinx.coroutines.launch
import org.WenuLink.adapters.AircraftHandler
import org.WenuLink.adapters.AsyncUtils
import org.WenuLink.controllers.MAVLinkController
import kotlin.getValue

class MAVLinkService (
    aircraft: AircraftHandler
) {
    companion object {
        var isEnabled: Boolean = true
            private set
    }
    private val logger by taggedLogger("MAVLinkService")

    private var mavlinkScope: CoroutineScope? = null
    private var controller: MAVLinkController = MAVLinkController(aircraft)
    private var endpointAddress = "192.168.1.220:14550"
    private var client: MAVLinkClient? = null
    private var listeningJob: Job? = null
    private var sendingJob: Job? = null
    var isReady: Boolean = false
        private set
    val hasStationConnected: Boolean
        get() = controller.isStationConnected()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    fun updateServerAddress(serverAddress: String) {
        endpointAddress = serverAddress
    }

    fun clientExists(): Boolean = client != null

    fun isServiceRunning(): Boolean {
        val hasJob = listeningJob?.isActive ?: false || sendingJob?.isActive ?: false
        return clientExists() && hasJob
    }

    fun clientCanStart(): Boolean = clientExists() && !isServiceRunning()

    fun isServiceStop(): Boolean = listeningJob == null && sendingJob == null

    fun createClient() {
        if (clientExists()){
            logger.d { "MAVLinkClient already created for udp://$endpointAddress." }
            return
        }
        logger.d { "Creating MAVLinkClient for udp://$endpointAddress." }

        val targetIp = endpointAddress.split(":")[0]
        val targetPort = endpointAddress.split(":")[1].toInt()

        client = MAVLinkClient(targetIp, targetPort)
    }

    fun destroyClient() {
        if (client == null) return
        client?.closeSocket()
        client = null
        logger.d { "MAVLinkClient ended." }
    }

    fun launchListeningJob(): Job {
        // Start listening for messages
        return mavlinkScope!!.launch {
            client?.startListening(controller::processMessage)
        }
    }

    fun launchSendingJob(): Job {
        // Start sending messages
        return mavlinkScope!!.launch {
            client?.startSending(controller::sendMessages)
        }
    }

    suspend fun launchService(onResult: (String?) -> Unit) {
        logger.d { "Requesting to launch MAVLinkService." }
        // notify UI
        _isRunning.value = true
        // Start client
        createClient()
        if (!clientCanStart()) {
            logger.i { "MAVLinkClient is not ready, possibly is disabled or already running." }
            onResult("MAVLinkClient is not ready, possibly is disabled or already running.")
            return
        }
        // Initialize controllers
        controller.attachClient(client!!)
        // IO jobs and scope
        mavlinkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        listeningJob = launchListeningJob()
        sendingJob = launchSendingJob()

        logger.d { "MAVLinkService (ready?=$isReady) (GCS?=${hasStationConnected}) (listening?=${listeningJob?.isActive}) (sending?=${sendingJob?.isActive})" }

        // Wait for station's heartbeat or shutdown if no received after a while
        val currentStationConnected = controller.waitGroundStation(5000L)
        if (!currentStationConnected) {
            onResult("No ground control station found, stoping")
            stopService()
            return
        }

        logger.d { "MAVLinkService (ready?=$isReady) (GCS?=${hasStationConnected}) (listening?=${listeningJob?.isActive}) (sending?=${sendingJob?.isActive})" }

        // Wait for service's boot up
        val hasParams = controller.loadParameters()
        if (hasParams) logger.d { "Parameters loaded" }

        mavlinkScope!!.launch {
            isReady = controller.waitSystemReady(60000L)
            if (isReady) controller.notifySystemReady()

            logger.d { "MAVLinkService (ready?=$isReady) (GCS?=${hasStationConnected}) (listening?=${listeningJob?.isActive}) (sending?=${sendingJob?.isActive})" }

            val hasHome = controller.waitHomePosition()
            if (hasHome) logger.d { "Home position acquired" }
            onResult(null)
        }
    }

    suspend fun stopClient() {
        if (isServiceStop()) return // nothing to stop

        mavlinkScope?.launch {
            logger.d { "Stop client listening" }
            client?.stopListening()
            listeningJob?.join()
        }
        mavlinkScope?.launch {
            logger.d { "Stop client sending" }
            client?.stopSending()
            sendingJob?.join()
        }
        logger.d { "Waiting 5s for client to stop" }
        val hasStop = AsyncUtils.waitTimeout(500L, 5000L, ::clientCanStart)
        if (!hasStop) logger.i { "Forcing stop client" }
        mavlinkScope?.cancel()
        sendingJob = null
        listeningJob = null
    }

    suspend fun stopService() {
        logger.d { "Requesting to stop MAVLinkService." }
        // Wait for stop client
        stopClient()
        destroyClient()
        _isRunning.value = false
        isReady = false
    }

}
