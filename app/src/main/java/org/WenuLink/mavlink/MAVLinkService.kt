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
import org.WenuLink.adapters.ServiceAddress
import org.WenuLink.adapters.WenuLinkHandler
import org.WenuLink.commands.CommandResult
import org.WenuLink.commands.UnitResult

class MAVLinkService(handler: WenuLinkHandler) {
    companion object {
        var isEnabled = true
            private set
    }
    private val logger by taggedLogger(MAVLinkService::class.java.simpleName)

    private var client: MAVLinkClient? = null
    private var controller = MAVLinkController(handler)
    private var groundControlStation = ServiceAddress("192.168.1.220", 14550, "UDP")
    private var messagesScope: CoroutineScope? = null
    private var listeningJob: Job? = null
    private var sendingJob: Job? = null
    var isReady = false
        private set
    val hasStationConnected
        get() = controller.isStationConnected()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    fun updateGCSAddress(serverAddress: String) {
        val (ip, port) = serverAddress.split(":")
        groundControlStation = ServiceAddress(ip, port.toInt(), "UDP")
    }

    fun clientExists(): Boolean = client != null

    fun isServiceRunning(): Boolean =
        clientExists() && (listeningJob?.isActive == true || sendingJob?.isActive == true)

    fun clientCanStart(): Boolean = clientExists() && !isServiceRunning()

    fun isServiceStop(): Boolean = listeningJob == null && sendingJob == null

    fun createClient() {
        if (clientExists()) {
            logger.d { "MAVLinkClient already created for $groundControlStation." }
            return
        }
        logger.d { "Creating MAVLinkClient for $groundControlStation." }

        client = MAVLinkClient(groundControlStation.ip, groundControlStation.port)
    }

    fun destroyClient() {
        val c = client ?: return
        c.closeSocket()
        client = null
        logger.d { "MAVLinkClient ended." }
    }

    fun launchListeningJob(): Job = // Start listening for messages
        messagesScope!!.launch {
            client?.startListening(controller::processMessage)
        }

    fun launchSendingJob(): Job = // Start sending messages
        messagesScope!!.launch {
            client?.startSending(controller::sendMessages)
        }

    suspend fun launchService(onResult: (UnitResult) -> Unit) {
        logger.d { "Requesting to launch MAVLinkService." }
        // notify UI
        _isRunning.value = true
        // Start client
        createClient()
        if (!clientCanStart()) {
            onResult(
                CommandResult.error("MAVLinkClient not ready, possibly disabled or already running.")
            )
            return
        }
        // Initialize controllers
        controller.attachClient(client!!)
        // IO jobs and scope
        messagesScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
            onResult(CommandResult.error("No ground control station found, stoping"))
            stopService()
            return
        }

        logger.d {
            "MAVLinkService (ready?=$isReady) " +
                "(GCS?=$hasStationConnected) " +
                "(listening?=${listeningJob?.isActive}) " +
                "(sending=${sendingJob?.isActive})"
        }

        // Wait for parameters and mission items request
        messagesScope?.launch {
            // TODO: move timeout to a UserPreference due to user's local network latency
            isReady = controller.waitServicesRequest(30000L)

            logger.d {
                "MAVLinkService (ready?=$isReady) " +
                    "(GCS?=$hasStationConnected) " +
                    "(listening?=${listeningJob?.isActive}) " +
                    "(sending=${sendingJob?.isActive})"
            }

            onResult(CommandResult.ok)

            // non-blocking wait for home position to report GPS_GLOBAL_ORIGIN
            controller.waitHomePosition()
        }
    }

    suspend fun stopClient() {
        if (isServiceStop()) return // nothing to stop

        messagesScope?.launch {
            logger.d { "Stop client listening" }
            client?.stopListening()
            listeningJob?.join()
        }
        messagesScope?.launch {
            logger.d { "Stop client sending" }
            client?.stopSending()
            sendingJob?.join()
        }
        logger.d { "Waiting 5s for client to stop" }
        val hasStop = AsyncUtils.waitTimeout(500L, 5000L, ::clientCanStart)
        if (!hasStop) logger.i { "Forcing stop client" }
        messagesScope?.cancel()
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
