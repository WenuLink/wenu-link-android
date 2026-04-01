package org.WenuLink.adapters

import io.getstream.log.taggedLogger
import kotlin.coroutines.resume
import kotlin.getValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.WenuLink.adapters.aircraft.AircraftCommand
import org.WenuLink.adapters.aircraft.AircraftHandler
import org.WenuLink.adapters.aircraft.ArduCopterFlightMode
import org.WenuLink.adapters.aircraft.BootCommand
import org.WenuLink.adapters.aircraft.ControlAuthority
import org.WenuLink.adapters.aircraft.ShutdownCommand
import org.WenuLink.adapters.camera.CameraCommand
import org.WenuLink.adapters.camera.CameraHandler
import org.WenuLink.adapters.camera.CameraMetadata
import org.WenuLink.adapters.mission.MissionCommand
import org.WenuLink.adapters.mission.MissionHandler
import org.WenuLink.commands.CommandHandler

sealed interface WenuLinkCommand {
    data class Aircraft(val command: AircraftCommand) : WenuLinkCommand
    data class Mission(val command: MissionCommand) : WenuLinkCommand
    data class Camera(val command: CameraCommand) : WenuLinkCommand
    data class Request(val command: RequestCommand) : WenuLinkCommand
}

class WenuLinkHandler : CommandHandler<WenuLinkHandler>() {
    companion object {
        private var mInstance: WenuLinkHandler? = null

        fun getInstance(): WenuLinkHandler {
            if (mInstance == null) {
                mInstance = WenuLinkHandler()
            }

            return mInstance!!
        }
    }

    private val logger by taggedLogger(WenuLinkHandler::class.java.simpleName)
    private var monitorJob: Job? = null
    val aircraft = AircraftHandler.getInstance()
    val mission = MissionHandler.getInstance()
    val camera = CameraHandler.getInstance()
    val isTelemetryActive: Boolean get() = aircraft.telemetry.isActive()
    val hasActiveJoystickInput: Boolean
        get() = aircraft.telemetry.getRCData()?.hasCenteredJoystick() == false
    val isAircraftPowerOn: Boolean get() = !aircraft.isPowerOff && monitorJob != null
    val availableCameras: List<CameraMetadata> get() = camera.availableCameras.toList()

    override fun registerScope(scope: CoroutineScope) {
        aircraft.registerScope(scope)
        mission.registerScope(scope)
        camera.registerScope(scope)
        startMonitorJob(scope)
    }

    override fun unload() {
        aircraft.dispatchCommand(ShutdownCommand(false)) {
            // Stop monitor
            monitorJob?.cancel()
            monitorJob = null
        }
        super.unload()
    }

    fun safetyChecks() {
        // --- Safety hooks ---
        if (hasActiveJoystickInput) {
            // stop everything to if any joystick is moved
            manualControl()
        }

        if (!aircraft.sensorsHealthy && aircraft.state.isFlying()) {
            logger.w { "Sensors lost during flight!" }
            // you could trigger RTL or LAND here
        }

        if (!isTelemetryActive) {
            logger.w { "Unexpected telemetry stop!" }
            // you could trigger RTL or LAND here
        }
    }

    private fun startMonitorJob(scope: CoroutineScope) {
        monitorJob?.cancel()

        monitorJob = scope.launch {
            while (isActive) {
                try {
                    // --- Sync real aircraft state ---
                    launch { aircraft.syncState() }

                    launch { mission.syncState() }

                    launch { safetyChecks() }

                    modeHooks(aircraft.copterFlightMode)
                } catch (e: Exception) {
                    logger.e { "Guard loop error: ${e.message}" }
                    // emergency land? manual control?
                }

                delay(200L) // tune this (100–500ms is reasonable)
            }
        }
    }

    fun dispatchCommand(cmd: WenuLinkCommand, onResult: (String?) -> Unit = {}) {
        when (cmd) {
            is WenuLinkCommand.Aircraft -> aircraft.dispatchCommand(cmd.command, onResult)
            is WenuLinkCommand.Mission -> mission.dispatchCommand(cmd.command, onResult)
            is WenuLinkCommand.Camera -> camera.dispatchCommand(cmd.command, onResult)
            is WenuLinkCommand.Request -> dispatchCommand(cmd.command, onResult)
        }
    }

    suspend fun dispatchAndAwait(cmd: WenuLinkCommand): String? =
        suspendCancellableCoroutine { cont ->
            dispatchCommand(cmd) { result ->
                if (cont.isActive) {
                    cont.resume(result)
                }
            }
        }

    suspend fun bootAircraft(): String? {
        val bootError = dispatchAndAwait(WenuLinkCommand.Aircraft(BootCommand(10000L)))
        if (bootError == null) manualControl()
        return bootError
    }

    fun controlTransition(authority: ControlAuthority) {
        // Decide policy: reject or stop mission
        if (!mission.isNewControlAuthority(authority)) return
        // Always stop everything
        cancelCommand()
        logger.d { "Control transition: ${mission.controlAuthority}->$authority" }
        mission.setControlAuthority(authority)
    }

    fun manualControl() {
        controlTransition(ControlAuthority.REMOTE_CONTROLLER)

        aircraft.requestMode(ArduCopterFlightMode.STABILIZE)
            .onFailure {
                logger.w { "Manual control mode rejected: ${it.message}" }
            }
    }

    fun pauseCommand() {
        mission.pauseCommand()
    }

    fun resumeCommand() {
        mission.resumeCommand()
    }

    fun cancelCommand() {
        logger.d { "Attempting to cancel active command" }
        stopCommand()
        aircraft.stopCommand()
        mission.stopCommand()
        camera.stopCommand()
    }

    fun stopAndDispatch(command: RequestCommand, onResult: (String?) -> Unit = {}) {
        if (currentCommand == command) return
        cancelCommand()
        dispatchCommand(command, onResult)
    }

    private fun modeHooks(copterFlightMode: ArduCopterFlightMode) = when (copterFlightMode) {
        ArduCopterFlightMode.BRAKE -> pauseCommand()
        ArduCopterFlightMode.AUTO -> resumeCommand()
        ArduCopterFlightMode.LAND -> stopAndDispatch(RequestLand(true))
        ArduCopterFlightMode.RTL -> stopAndDispatch(RequestGoHome)
        else -> {}
    }
}
