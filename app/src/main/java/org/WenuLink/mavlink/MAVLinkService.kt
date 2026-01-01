package org.WenuLink.mavlink

import android.util.Log
import org.WenuLink.adapters.ParameterController
import com.MAVLink.Messages.MAVLinkMessage
import com.MAVLink.common.msg_autopilot_version.MAVLINK_MSG_ID_AUTOPILOT_VERSION
import com.MAVLink.common.msg_command_long.MAVLINK_MSG_ID_COMMAND_LONG
import com.MAVLink.common.msg_system_time.MAVLINK_MSG_ID_SYSTEM_TIME
import com.MAVLink.enums.MAV_CMD
import com.MAVLink.enums.MAV_RESULT
import com.MAVLink.minimal.msg_heartbeat.MAVLINK_MSG_ID_HEARTBEAT
import io.getstream.log.taggedLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.WenuLink.adapters.CommandController
import org.WenuLink.adapters.ConnectionController
import kotlin.getValue

class MAVLinkService {
    companion object {
        var isEnabled: Boolean = true
            private set
    }
    private val logger by taggedLogger("MAVLinkService")

    private val TAG: String = MAVLinkService::class.java.simpleName
    private lateinit var client: MAVLinkClient
    private var endpointAddress = "192.168.1.220:14550"
    private var gcsLastTimestamp: Long = 0
    private var startTimestamp: Long = System.currentTimeMillis()
    private lateinit var commandController: CommandController
    private lateinit var connectionController: ConnectionController
    private var controllers: List<MAVLinkController> = emptyList()
    private var runningJob: Job? = null
    var delayTime: Long = 100L // Called ever 100ms...
        private set
    var isServiceUp: Boolean = false
        private set

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    fun runProcess(isRunning: Boolean) {
        logger.d { "runProcess($isRunning)" }
        _isRunning.value = isRunning
    }

    fun updateServerAddress(serverAddress: String) {
        endpointAddress = serverAddress
    }

    fun canStartClient(): Boolean = isEnabled && !isServiceUp

    fun createClient(serviceScope: CoroutineScope) {
        if (!isEnabled) {
            logger.i { "Unable to start client, MAVLink not enabled." }
            return
        }

        logger.i { "Starting MAVLinkClient for $endpointAddress" }

        if (::client.isInitialized) return

        val targetIp = endpointAddress.split(":")[0]
        val targetPort = endpointAddress.split(":")[1].toInt()
        client = MAVLinkClient(
            targetIp = targetIp, targetPort = targetPort,
            onMessageReceived = this::messageCallback
        )

        // Register controllers for each MAVLink service
        commandController = CommandController(client)
        connectionController = ConnectionController(client, serviceScope)
        controllers = emptyList()
        controllers += connectionController
        controllers += ParameterController(client)

        logger.d { "Client created" }
    }

    fun registerScope(serviceScope: CoroutineScope) {
        isRunning.distinctUntilChangedBy { it }
            .onEach {
                if (it) run(serviceScope)
                else stop()
                logger.d { "MAVLinkClient running: $it" }
            }
            .launchIn(serviceScope)
    }

    private fun createClientJob(serviceScope: CoroutineScope) {
        logger.d { "Creating MAVLink client's Job" }
        runningJob = serviceScope.launch {
            client.start { success, error ->
                startTimestamp = System.currentTimeMillis()
                isServiceUp = success
                logger.d { "MAVLinkClient's up: $isServiceUp" }
            }
            while (isActive) {
                try {
                    connectionController.tick(delayTime)
                } catch (e: Exception) {
                    logger.e { "Error in data tick $e" }
                } finally {
                    delay(delayTime)
                }
            }
        }
    }

    fun run(serviceScope: CoroutineScope) {
        logger.d { "run" }
        if (!::client.isInitialized) {
            logger.i { "Unable to run service, no MAVLink client." }
            return
        }

        if (isServiceUp) return

        if (connectionController.isTelemetryRunning()) return

        // Only start client after telemetry
        connectionController.startTelemetry { error ->
            if (error == null) {
                logger.i { "Telemetry started" }
                createClientJob(serviceScope)
            }
            else logger.i { "Error in Telemetry: $error" }
            // TODO: else { notice problem with toast }
        }
    }

    fun stop() {
        logger.d { "stop" }

        if (!isServiceUp) return

        // stopClientJob
        runningJob?.cancel()
        runningJob = null

        if (!::client.isInitialized) return

        client.stop { success, error ->
            logger.d { "MAVLinkClient's up: ${!success}" }
            connectionController.stopTelemetry {
                isServiceUp = !success
                logger.i { "Telemetry stopped" }
            }
        }
    }

    // https://ardupilot.org/copter/docs/ArduCopter_MAVLink_Messages.html
    // https://mavlink.io/en/services/
    fun messageCallback(msg: MAVLinkMessage) {
        Log.i(TAG, "Processing MAVLink message ID: ${msg.msgid}")
        // Process message with registered Controllers
        controllers.forEach { it.processMessage(msg) }
        // Process other messages
        when (msg.msgid) {
            MAVLINK_MSG_ID_HEARTBEAT -> gcsLastTimestamp = System.currentTimeMillis()
            MAVLINK_MSG_ID_SYSTEM_TIME -> connectionController.sendSystemTime(startTimestamp)
            // TODO: Unhandled message ID: 43
            // MAVLINK_MSG_ID_MISSION_REQUEST_LIST -> {}
            // TODO: Unhandled message ID: 44
            // MAVLINK_MSG_ID_MISSION_COUNT -> {}
            MAVLINK_MSG_ID_AUTOPILOT_VERSION -> commandController.sendCommandAck(
                MAV_CMD.MAV_CMD_REQUEST_AUTOPILOT_CAPABILITIES,
                MAV_RESULT.MAV_RESULT_ACCEPTED
            )

            MAVLINK_MSG_ID_COMMAND_LONG -> commandController.processMessage(msg)

            else -> {
                Log.e(TAG, "Unhandled message ID: ${msg.msgid}")
                commandController.sendCommandAck(msg.msgid)
            }
        }
    }

}
