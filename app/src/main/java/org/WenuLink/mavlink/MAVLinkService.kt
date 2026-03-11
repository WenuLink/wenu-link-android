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
import org.WenuLink.adapters.AsyncUtils
import org.WenuLink.controllers.MAVLinkController

data class Endpoint(val ip: String, val port: Int)

class MAVLinkService {
    companion object {
        var isEnabled: Boolean = true
            private set
    }
    private val logger by taggedLogger(MAVLinkService::class.java.simpleName)

    private var client: MAVLinkClient? = null
    private lateinit var controller: MAVLinkController
    private var endpoint = Endpoint("192.168.1.220", 14550)
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
        val (ip, port) = serverAddress.split(":")
        endpoint = Endpoint(ip, port.toInt())
    }

    fun hasStationConnected(): Boolean =
        ::controller.isInitialized && controller.isStationConnected()

    fun hasTelemetryRunning(): Boolean =
        ::controller.isInitialized && controller.isTelemetryRunning()

    fun clientExists(): Boolean = client != null

    fun isServiceRunning(): Boolean =
        clientExists() && (listeningJob?.isActive == true || sendingJob?.isActive == true)

    fun clientCanStart(): Boolean = clientExists() && !isServiceRunning()

    fun isServiceStop(): Boolean = listeningJob == null && sendingJob == null

    fun createClient(serviceScope: CoroutineScope) {
        logger.d { "Creating MAVLinkClient for udp://${endpoint.ip}:${endpoint.port}." }

        if (clientExists()) return

        val newClient = MAVLinkClient(endpoint.ip, endpoint.port)
        client = newClient
        controller = MAVLinkController(newClient, serviceScope)

        isRunning.distinctUntilChangedBy { it }
            .onEach {
                logger.d { "Requesting to ${if (it) "launch" else "stop"} MAVLinkService." }
                if (it) {
                    launchService()
                } else {
                    stopService()
                }
            }
            .launchIn(serviceScope)
    }

    fun destroyClient() {
        val c = client ?: return
        logger.d { "MAVLinkClient ended." }
        c.closeSocket()
        client = null
    }

    suspend fun launchService() {
        if (!clientCanStart()) {
            logger.i { "MAVLinkClient not ready, possibly disabled or already running." }
            return
        }

        if (!hasTelemetryRunning()) {
            logger.i { "Telemetry not launched." }
            return
        }
        logger.i { "Telemetry broadcasting data." }

        // Input and Output jobs in an IO scope
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        mavlinkScope = scope
        listeningJob = scope.launch { controller.launchListening() }
        sendingJob = scope.launch { controller.launchSending(waitingTime) }

        logger.d {
            "MAVLinkService (ready?=$isReady) " +
                "(GCS?=${hasStationConnected()}) " +
                "(listening?=${listeningJob?.isActive}) " +
                "(sending=${sendingJob?.isActive})"
        }

        // Try to connect to station
        val currentStationConnected = controller.waitGroundStation(30000L)
        if (!currentStationConnected) {
            logger.w { "No GCS connection established, continuing anyway." }
        }

        logger.d {
            "MAVLinkService (ready?=$isReady) " +
                "(GCS?=${hasStationConnected()}) " +
                "(listening?=${listeningJob?.isActive}) " +
                "(sending=${sendingJob?.isActive})"
        }

        val hasParams = controller.waitParameters()
        if (hasParams) logger.d { "Parameters loaded" }

        isReady = controller.waitSystemReady(60000L)
        if (isReady) logger.w { "System not ready, unlocking telemetry anyway." }
        controller.notifySystemReady()

        logger.d {
            "MAVLinkService (ready?=$isReady) " +
                "(GCS?=${hasStationConnected()}) " +
                "(listening?=${listeningJob?.isActive}) " +
                "(sending=${sendingJob?.isActive})"
        }

        val hasHome = controller.waitHomePosition()
        if (hasHome) logger.d { "Home position acquired" }
    }

    suspend fun stopService() {
        // Wait for stop client
        mavlinkScope?.let {
            it.launch {
                controller.shutdown()
                sendingJob?.join()
                listeningJob?.join()
            }.join()
            it.cancel()
            mavlinkScope = null
        }

        isReady = false
        logger.d {
            "MAVLinkService (ready?=$isReady) " +
                "(GCS?=${hasStationConnected()}) " +
                "(listening?=${listeningJob?.isActive}) " +
                "(sending=${sendingJob?.isActive})"
        }
        sendingJob = null
        listeningJob = null
    }

    suspend fun waitStart() {
        AsyncUtils.waitReady(isReady = ::isServiceRunning)
        logger.i { "MAVLinkService started: ${isServiceRunning()}" }
    }

    suspend fun waitStop() {
        AsyncUtils.waitReady(isReady = ::isServiceStop)
        logger.i { "MAVLinkService stop: ${isServiceStop()}" }
    }
}
