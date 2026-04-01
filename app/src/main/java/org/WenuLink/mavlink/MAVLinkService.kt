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
import org.WenuLink.adapters.AsyncUtils
import org.WenuLink.adapters.aircraft.AircraftHandler
import org.WenuLink.controllers.MAVLinkController

data class Endpoint(val ip: String, val port: Int) {
    fun toUrl(): String = "udp://$ip:$port"
}

class MAVLinkService(aircraft: AircraftHandler) {
    companion object {
        var isEnabled: Boolean = true
            private set
    }
    private val logger by taggedLogger(MAVLinkService::class.java.simpleName)

    private var client: MAVLinkClient? = null
    private var controller: MAVLinkController = MAVLinkController(aircraft)
    private var endpoint = Endpoint("192.168.1.220", 14550)
    private var mavlinkScope: CoroutineScope? = null
    private var listeningJob: Job? = null
    private var sendingJob: Job? = null
    var isReady: Boolean = false
        private set
    val hasStationConnected: Boolean
        get() = controller.isStationConnected()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    fun updateServerAddress(endpoint: Endpoint) {
        this.endpoint = endpoint
    }

    fun clientExists(): Boolean = client != null

    fun isServiceRunning(): Boolean =
        clientExists() && (listeningJob?.isActive == true || sendingJob?.isActive == true)

    fun clientCanStart(): Boolean = clientExists() && !isServiceRunning()

    fun isServiceStop(): Boolean = listeningJob == null && sendingJob == null

    fun createClient() {
        if (clientExists()) {
            logger.d { "MAVLinkClient already created for udp://${endpoint.toUrl()}." }
            return
        }
        logger.d { "Creating MAVLinkClient for udp://${endpoint.toUrl()}." }

        client = MAVLinkClient(endpoint.ip, endpoint.port)
    }

    fun destroyClient() {
        val c = client ?: return
        c.closeSocket()
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
            onResult("MAVLinkClient not ready, possibly disabled or already running.")
            return
        }
        // Initialize controllers
        controller.attachClient(client!!)
        // IO jobs and scope
        mavlinkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        listeningJob = launchListeningJob()
        sendingJob = launchSendingJob()

        logger.d {
            "MAVLinkService (ready?=$isReady) " +
                "(GCS?=$hasStationConnected) " +
                "(listening?=${listeningJob?.isActive}) " +
                "(sending=${sendingJob?.isActive})"
        }

        // Wait for station's heartbeat or shutdown if no received after a while
        val currentStationConnected = controller.waitGroundStation(30000L)
        if (!currentStationConnected) {
            onResult("No ground control station found, stoping")
            stopService()
            return
        }

        logger.d {
            "MAVLinkService (ready?=$isReady) " +
                "(GCS?=$hasStationConnected) " +
                "(listening?=${listeningJob?.isActive}) " +
                "(sending=${sendingJob?.isActive})"
        }

        // Wait for service's boot up
        val hasParams = controller.loadParameters()
        if (hasParams) logger.d { "Parameters loaded" }

        mavlinkScope?.launch {
            // TODO: move timeout to a UserPreference due to user's local network latency
            isReady = controller.waitServicesRequest(30000L)

            logger.d {
                "MAVLinkService (ready?=$isReady) " +
                    "(GCS?=$hasStationConnected) " +
                    "(listening?=${listeningJob?.isActive}) " +
                    "(sending=${sendingJob?.isActive})"
            }

            onResult(null)

            // non-blocking wait for home position to report GPS_GLOBAL_ORIGIN
            controller.waitHomePosition()
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
