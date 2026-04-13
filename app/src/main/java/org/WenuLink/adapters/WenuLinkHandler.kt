package org.WenuLink.adapters

import io.getstream.log.taggedLogger
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.WenuLink.adapters.aircraft.AircraftHandler
import org.WenuLink.adapters.aircraft.AircraftState
import org.WenuLink.adapters.aircraft.ArduCopterFlightMode
import org.WenuLink.adapters.aircraft.BootCommand
import org.WenuLink.adapters.aircraft.ControlAuthority
import org.WenuLink.adapters.aircraft.ControlAuthorityType
import org.WenuLink.adapters.aircraft.ShutdownCommand
import org.WenuLink.adapters.camera.CameraHandler
import org.WenuLink.adapters.camera.CameraMetadata
import org.WenuLink.adapters.mission.MissionHandler
import org.WenuLink.adapters.mission.PauseActionCommand
import org.WenuLink.adapters.mission.PauseWaypointMission
import org.WenuLink.adapters.mission.ResumeActionCommand
import org.WenuLink.adapters.mission.ResumeWaypointMission
import org.WenuLink.adapters.mission.StopWaypointMission
import org.WenuLink.commands.CommandHandler
import org.WenuLink.commands.UnitResult

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
    private var controlAuthority = ControlAuthority(ControlAuthorityType.NONE)
    var startTimestamp: Long = -1
        private set
    val systemBootTime: Long
        get() = if (startTimestamp == -1L) {
            -1
        } else {
            System.currentTimeMillis() - startTimestamp
        }
    val aircraft by lazy { AircraftHandler.getInstance() }
    val mission by lazy { MissionHandler.getInstance() }
    val camera by lazy { CameraHandler.getInstance() }
    val aircraftState: AircraftState get() = aircraft.state
    val aircraftModel: String get() = aircraft.telemetry.getAircraftModelName()
    val isTelemetryActive: Boolean get() = aircraft.telemetry.isActive()
    val telemetryStateFlow: StateFlow<Boolean> get() = aircraft.telemetry.isBroadcasting
    val hasActiveJoystickInput: Boolean
        get() = aircraft.telemetry.getRCData()?.hasCenteredJoystick() == false
    val isAircraftPowerOn: Boolean get() = !aircraft.isPowerOff &&
        camera.wasInitialized &&
        monitorJob != null
    val availableCameras: List<CameraMetadata> get() = camera.availableCameras.toList()
    var onImageCaptured: ((cameraId: Int, seqIndex: Int) -> Unit)? = null

    override fun registerScope(scope: CoroutineScope) {
        aircraft.registerScope(scope)
        mission.registerScope(scope)
        camera.registerScope(scope)
        startMonitorJob(scope)
        startCommandProcessor(scope, this@WenuLinkHandler, logger)
    }

    override fun unload() {
        aircraft.dispatchCommand(ShutdownCommand(false)) {
            // Stop monitor
            monitorJob?.cancel()
            monitorJob = null
        }
        onImageCaptured = null
        super.unload()
    }

    fun enableSimulation(enable: Boolean) = aircraft.telemetry.enableSimulation(enable)

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
                    launch {
                        aircraft.syncState()
                        modeHooks()
                    }

                    launch {
                        mission.syncState()
                        missionHooks()
                    }

                    launch {
                        cameraHooks()
                    }

                    launch {
                        safetyChecks()
                    }
                } catch (e: Exception) {
                    logger.e { "Guard loop error: ${e.message}" }
                    // emergency land? manual control?
                }

                delay(200L) // tune this (100–500ms is reasonable)
            }
        }
    }

    fun dispatchCommand(cmd: WenuLinkCommand, onResult: (UnitResult) -> Unit = {}) {
        when (cmd) {
            is WenuLinkCommand.Aircraft -> aircraft.dispatchCommand(cmd.command, onResult)
            is WenuLinkCommand.Mission -> mission.dispatchCommand(cmd.command, onResult)
            is WenuLinkCommand.Camera -> camera.dispatchCommand(cmd.command, onResult)
            is WenuLinkCommand.Request -> dispatchCommand(cmd.command, onResult)
        }
    }

    suspend fun dispatchAndAwait(cmd: WenuLinkCommand): UnitResult =
        suspendCancellableCoroutine { cont ->
            dispatchCommand(cmd) { result ->
                if (cont.isActive) {
                    cont.resume(result)
                }
            }
        }

    suspend fun bootAircraft(): UnitResult {
        val bootResult = dispatchAndAwait(WenuLinkCommand.Aircraft(BootCommand(10_000L)))
        if (bootResult.isOk) {
            manualControl()
            startTimestamp = System.currentTimeMillis()
        }
        return bootResult
    }

    fun setAuthority(authority: ControlAuthorityType): ControlAuthority {
        controlAuthority = controlAuthority.copy(authorityType = authority)
        return controlAuthority
    }

    fun dispatchControlAuthority(authority: ControlAuthorityType) {
        // Decide policy: reject or stop mission
        if (!controlAuthority.isNewAuthority(authority)) return
        // Always stop everything
        stopAllCommands()
        logger.d { "Control transition: ${controlAuthority.authorityType}->$authority" }
        setAuthority(authority)
    }

    fun manualControl() {
        dispatchControlAuthority(ControlAuthorityType.REMOTE_CONTROLLER)

        val result = aircraft.requestMode(ArduCopterFlightMode.STABILIZE)
        if (result.hasError) {
            logger.w { "Manual control mode rejected: ${result.errorReason}" }
        }
    }

    fun missionPause() {
        logger.i { "Pause mission" }
        when {
            controlAuthority.isWaypoint() ->
                mission.dispatchCommand(PauseWaypointMission) { result ->
                    if (result.hasError) {
                        logger.i {
                            "Unable to pause the mission at ${mission.state.currentSequence}: ${result.errorReason}"
                        }
                    }
                }

            controlAuthority.isCommand() ->
                mission.dispatchCommand(PauseActionCommand) { result ->
                    if (result.hasError) {
                        logger.i { "Unable to pause the command: ${result.errorReason}" }
                    }
                }
        }
    }

    fun missionResume() {
        logger.i { "Resume mission" }
        when {
            controlAuthority.isWaypoint() ->
                mission.dispatchCommand(ResumeWaypointMission) { result ->
                    if (result.hasError) {
                        logger.i {
                            "Unable to resume the mission: ${result.errorReason}"
                        }
                    }
                }

            controlAuthority.isCommand() ->
                mission.dispatchCommand(ResumeActionCommand) { result ->
                    if (result.hasError) {
                        logger.i {
                            "Unable to resume the command: ${result.errorReason}"
                        }
                    }
                }
        }
    }

    fun missionStop() {
        logger.d { "Stop mission" }
        when {
            controlAuthority.isWaypoint() -> mission.dispatchCommand(
                StopWaypointMission
            ) { result ->
                if (result.hasError) {
                    logger.i {
                        "Unable to stop the mission: ${result.errorReason}"
                    }
                }
            }

            controlAuthority.isCommand() -> mission.stopCommand()
        }
    }

    fun missionClear() {
        missionStop()
        mission.clear()
    }

    fun stopAllCommands() {
        logger.d { "Attempting to cancel active command" }
        aircraft.stopCommand()
        missionStop()
        camera.stopCommand()
        this.stopCommand()
    }

    fun stopAndDispatch(command: RequestCommand, onResult: (UnitResult) -> Unit = {}) {
        if (currentCommand == command) return
        missionStop()
        dispatchCommand(command, onResult)
    }

    private fun modeHooks() = when (aircraft.state.flightMode) {
        ArduCopterFlightMode.BRAKE -> missionPause()
        ArduCopterFlightMode.AUTO -> missionResume()
        ArduCopterFlightMode.LAND -> stopAndDispatch(RequestLand(true))
        ArduCopterFlightMode.RTL -> stopAndDispatch(RequestGoHome(true))
        else -> {}
    }

    private fun missionHooks() = when {
        mission.state.isComplete() -> dispatchControlAuthority(ControlAuthorityType.NONE)
        mission.state.unvisitedSequence -> mission.processNode()
        else -> {}
    }

    private fun cameraHooks() {
        if (!camera.wasInitialized) return
        if (camera.consumeCaptureEvent()) {
            val cameraId = camera.availableCameras.firstOrNull()?.id ?: return
            onImageCaptured?.invoke(cameraId, camera.photoSeqIndex)
        }
    }
}
