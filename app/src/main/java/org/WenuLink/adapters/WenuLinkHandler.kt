package org.WenuLink.adapters

import io.getstream.log.taggedLogger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
import org.WenuLink.commands.CommandResult
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
    val hasActiveJoystickInput: Boolean
        get() = aircraft.telemetry.getRCData()?.hasCenteredJoystick() == false
    val isAircraftPowerOn: Boolean get() = !aircraft.isPowerOff &&
        camera.wasInitialized &&
        monitorJob != null
    val availableCameras: List<CameraMetadata> get() = camera.availableCameras.toList()
    var onImageCaptured: ((cameraId: Int, seqIndex: Int) -> Unit)? = null

    override fun registerScope(scope: CoroutineScope) {
        aircraft.registerScope(scope)
        startCommandProcessor(scope, this@WenuLinkHandler, logger)
    }

    override fun unload() {
        aircraft.unload()
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
        // tune this (100–500ms is reasonable)
        val minimumDelay = 200L

        monitorJob = scope.launch {
            logger.d { "Starting monitor job" }
            var lastLoop = System.currentTimeMillis()
            while (!aircraft.isPowerOff) {
                try {
                    coroutineScope {
                        // --- Sync real aircraft state ---
                        launch {
                            aircraft.syncSensors()
                            if (aircraft.state.isFlying()) flyingModeHooks()
                        }

                        launch {
                            mission.syncState()
                            if (mission.state.isActive()) activeMissionHooks()
                        }

                        launch {
                            cameraHooks()
                        }

                        launch {
                            safetyChecks()
                        }
                    }
                } catch (_: CancellationException) {
                    logger.w { "Monitor loop CancellationException" }
                    currentCoroutineContext().ensureActive()
                } catch (e: Exception) {
                    logger.e { "Monitor loop error: ${e.message}" }
                    // emergency land? manual control?
                } finally {
                    // avoid delay for those cases where elapsed time > minimumDelay.
                    val dTime = max(0, minimumDelay - (System.currentTimeMillis() - lastLoop))
                    delay(dTime)
                    // logger.d { "Monitor loop time: ${System.currentTimeMillis() - lastLoop}ms" }
                    lastLoop = System.currentTimeMillis()
                }
            }
            logger.d { "Monitor job ended" }
        }
    }

    private fun stopMonitor() {
        logger.d { "Stop monitor job" }
        // Stop monitor
        monitorJob?.cancel()
        monitorJob = null
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

    suspend fun loadComponents(scope: CoroutineScope): UnitResult {
        // prevent cancel command when manualControl() after boot
        dispatchControlAuthority(ControlAuthorityType.REMOTE_CONTROLLER)

        val bootResult = dispatchAndAwait(WenuLinkCommand.Aircraft(BootCommand(30_000L)))
        if (bootResult.isOk) {
            manualControl()
            startTimestamp = System.currentTimeMillis()
            // Register listeners
            mission.registerScope(scope)
            camera.registerScope(scope)
            // Wait for camera be initialized
            AsyncUtils.waitTimeout(100L, 5000L) { camera.wasInitialized }
            startMonitorJob(scope)
        }

        return bootResult
    }

    suspend fun unloadComponents(strictTransition: Boolean = false): UnitResult {
        stopAllCommands()
        mission.unload()
        camera.unload()
        onImageCaptured = null
        stopMonitor()
        return dispatchAndAwait(WenuLinkCommand.Aircraft(ShutdownCommand(strictTransition)))
    }

    fun setAuthority(authority: ControlAuthorityType): ControlAuthority {
        controlAuthority = controlAuthority.copy(authorityType = authority)
        return controlAuthority
    }

    fun dispatchControlAuthority(authority: ControlAuthorityType): UnitResult {
        // Decide policy: reject or stop mission
        if (!controlAuthority.isNewAuthority(authority)) return CommandResult.ok

        if (controlAuthority.isRemote()) {
            logger.d { "Skip authority switch: remote active" }
            return CommandResult.error("Remote pilot has authority")
        }

        // Always stop everything
        stopAllCommands()
        logger.d { "Control transition: ${controlAuthority.authorityType}->$authority" }
        setAuthority(authority)
        return CommandResult.ok
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
        when {
            controlAuthority.isWaypoint() -> mission.dispatchCommand(
                StopWaypointMission
            ) { result ->
                if (result.hasError) {
                    logger.w {
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
        aircraft.stopCommand()
        missionStop()
        camera.stopCommand()
        this.stopCommand()
    }

    fun stopAndDispatch(command: RequestCommand) {
        if (currentCommand == command) return
        stopAllCommands()
        dispatchCommand(command) { result ->
            if (result.hasError) {
                logger.e { "Problem during command execution: ${result.errorReason}" }
            }
        }
    }

    private fun flyingModeHooks() = when (aircraft.state.flightMode) {
        ArduCopterFlightMode.BRAKE -> missionPause()
        ArduCopterFlightMode.AUTO -> missionResume()
        ArduCopterFlightMode.LAND -> stopAndDispatch(RequestLand(true))
        ArduCopterFlightMode.RTL -> stopAndDispatch(RequestGoHome(true))
        else -> {}
    }

    private fun activeMissionHooks() = when {
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
