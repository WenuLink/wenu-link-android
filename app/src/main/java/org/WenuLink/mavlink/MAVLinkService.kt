package org.WenuLink.mavlink

import android.util.Log
import org.WenuLink.adapters.ParameterController
import org.WenuLink.adapters.TelemetryHandler
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
import kotlin.getValue

class MAVLinkService {
    companion object {
        private var instance: MAVLinkService? = null
        var isEnabled: Boolean = true
            private set

        fun getInstance(): MAVLinkService {
            if (instance == null)
                instance = MAVLinkService()
            return instance!!
        }

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        fun runProcess(isRunning: Boolean) {
            _isRunning.value = isRunning
        }
    }
    private val logger by taggedLogger("MAVLinkService")

    private val TAG: String = MAVLinkService::class.java.simpleName
    private var startCallback: (Boolean, String?) -> Unit = { s, e -> }
    private var stopCallback: (Boolean, String?) -> Unit = { s, e -> }
    private lateinit var client: MAVLinkClient
    private var gcsLastTimestamp: Long = 0
    private var startTimestamp = 0L
    private val telemetry: TelemetryHandler = TelemetryHandler.getInstance()
    private lateinit var commandController: CommandController
    private lateinit var connectionController: ConnectionController
    private var controllers: List<MAVLinkController> = emptyList()
    private var ticks: Long = 0
    private lateinit var serviceScope: CoroutineScope
    private var runningJob: Job? = null

    private var endpointAddress = "192.168.1.220:14550"
    var delayTime: Long = 100L // Called ever 100ms...
        private set
    var isServiceUp: Boolean = false
        private set

    fun updateServerAddress(serverAddress: String) {
        endpointAddress = serverAddress
    }

    fun canStartClient(): Boolean = isEnabled

    fun getTelemetryFlow(): StateFlow<Boolean> = TelemetryHandler.isDataFlowing

    fun startClient(serviceScope: CoroutineScope) {
        if (!isEnabled) {
            logger.i { "Unable to start client, MAVLink not enabled." }
            return
        }
        this.serviceScope = serviceScope
        val targetIp = endpointAddress.split(":")[0]
        val targetPort = endpointAddress.split(":")[1].toInt()
        client = MAVLinkClient(
            targetIp = targetIp, targetPort = targetPort,
            onMessageReceived = this::messageCallback
        )
        commandController = CommandController(client)
        connectionController = ConnectionController(client)
        controllers += connectionController
        controllers += ParameterController(client)

        telemetry.registerListenerScope(serviceScope)

        isRunning.distinctUntilChangedBy { it }
            .onEach {
                if (it) {
                    telemetry.start(true) { success, error ->
                        // Only start client after telemetry
                        if (success) run()
                    }
                }
                else {
                    stop()
                    // stop telemetry at the end
                    telemetry.start(false) { s, e -> }
                }
                logger.d { "isRunning: $it" }
            }
            .launchIn(this.serviceScope)

        logger.d { "MAVLinkClient initialized for ${targetIp}:${targetPort}" }
    }

    fun registerStartCallback(onResult: (Boolean, String?) -> Unit) {
        startCallback = onResult
    }

    fun registerStopCallback(onResult: (Boolean, String?) -> Unit) {
        stopCallback = onResult
    }

    fun run() {
        if (!isEnabled) {
            logger.i { "Unable to start client, MAVLink not enabled." }
            return
        }

        if (isServiceUp) {
            return
        }

        runningJob = serviceScope.launch {
            client.start { success, error ->
                startTimestamp = System.currentTimeMillis()
                isServiceUp = success
                logger.d { "MAVLinkClient's up $isServiceUp" }
                startCallback(success, error)
            }
            while (isActive) {
                try {
                    tick()
                } catch (e: Exception) {
                    logger.e {"Error in data tick $e" }
                }
                delay(delayTime)
            }
        }
    }

    fun stop() {
        if (!isEnabled) {
            return
        }
        runningJob?.cancel()
        runningJob = null
        if (isServiceUp)
            client.stop { success, error ->
                isServiceUp = !success
                Log.d(TAG, "MAVLinkClient's up $isServiceUp")
                stopCallback(success, error)
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

    // https://ardupilot.org/copter/docs/ArduCopter_MAVLink_Messages.html#outgoing-messages
    fun tick() {
        if (!client.isReady()) {
            Log.e(TAG, "MAVLink client is not ready yet!.")
            return
        }

        val telemetryData = telemetry.getTelemetryData() ?: run {
            Log.w(TAG, "No telemetry data yet!")
            return
        }

        ticks += delayTime
        if (ticks % 100 == 0L) {
            connectionController.sendAttitude(telemetryData)
            connectionController.sendAltitude(telemetryData)
            connectionController.sendVibration()
            val rcData = telemetry.getRCData()
            if (rcData != null)
                connectionController.sendHUD(telemetryData, rcData)
        }
        if (ticks % 200 == 0L) {
            connectionController.sendGlobalPositionInt(telemetryData)
        }
        if (ticks % 300 == 0L) {
            connectionController.sendRawGPSInt(telemetryData)
            connectionController.sendRadioStatus()
//            client.checkRCChannels()  // prevent execution in case of RC input received
        }
        if (ticks % 1000 == 0L) {
            connectionController.sendHeartbeat(telemetryData)
            connectionController.sendSysStatus(telemetry.getAircraftBattery())
            connectionController.sendPowerStatus()
            connectionController.sendBatteryStatus(telemetry.getAircraftBattery())
//            mavLinkClient.checkLanding()  // DJI landing callback
        }
        if (ticks % 5000 == 0L) {
            val homePos = telemetry.getHomePosition()
            if (homePos != null)
                connectionController.sendHomePosition(
                    homePos.first,
                    homePos.second,
                    homePos.third
                )
        }
    }

}
