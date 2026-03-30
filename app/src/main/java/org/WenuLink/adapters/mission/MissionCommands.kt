package org.WenuLink.adapters.mission

import dji.common.model.LocationCoordinate2D
import dji.sdk.mission.timeline.actions.GoHomeAction
import dji.sdk.mission.timeline.actions.GoToAction
import dji.sdk.mission.timeline.actions.LandAction
import dji.sdk.mission.timeline.actions.MissionAction
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import org.WenuLink.adapters.aircraft.Coordinates3D
import org.WenuLink.adapters.commands.ICommand
import org.WenuLink.sdk.MissionActionManager
import org.WenuLink.sdk.MissionManager

sealed interface MissionCommand : ICommand<MissionHandler> {
    override fun validate(ctx: MissionHandler): String?
    override suspend fun execute(ctx: MissionHandler): String?
    override suspend fun onStop(ctx: MissionHandler)
}

data class UploadMissionCommand(
    private val assembler: MissionAssembler,
    private val flightSpeed: Float = 5.0f
) : MissionCommand {

    override fun validate(ctx: MissionHandler): String? = when {
        ctx.canCreateMission() -> null
        else -> "Upload not ready"
    }

    override suspend fun execute(ctx: MissionHandler): String? =
        suspendCancellableCoroutine { cont ->
            MissionManager.uploadMission(assembler.build(), flightSpeed) { success, error ->
                cont.resume(error)
            }

            cont.invokeOnCancellation {
                // Add SDK cancel if available
            }
        }

    override suspend fun onStop(ctx: MissionHandler) = MissionManager.clearMission()
}

data object StartWaypointMission : MissionCommand {

    override fun validate(ctx: MissionHandler): String? = when {
        ctx.canCreateMission() -> "No mission found"
        ctx.isMissionActive -> "Already started"
        ctx.canStartMission() -> null
        else -> "Not ready"
    }

    override suspend fun execute(ctx: MissionHandler): String? =
        suspendCancellableCoroutine { cont ->
            MissionManager.startMission { error ->
                cont.resume(error)
            }

            cont.invokeOnCancellation {
                // Add SDK cancel if available
            }
        }

    override suspend fun onStop(ctx: MissionHandler) = ctx.dispatchCommand(PauseWaypointMission)
}

data object PauseWaypointMission : MissionCommand {

    override fun validate(ctx: MissionHandler): String? = when {
        ctx.canPauseMission() -> null
        else -> "Not started"
    }

    override suspend fun execute(ctx: MissionHandler): String? =
        suspendCancellableCoroutine { cont ->
            MissionManager.pauseMission { error ->
                cont.resume(error)
            }

            cont.invokeOnCancellation {
                // Add SDK cancel if available
            }
        }

    override suspend fun onStop(ctx: MissionHandler) { }
}

data object ResumeWaypointMission : MissionCommand {

    override fun validate(ctx: MissionHandler): String? = when {
        ctx.canResumeMission() -> null
        else -> "Already in execution"
    }

    override suspend fun execute(ctx: MissionHandler): String? =
        suspendCancellableCoroutine { cont ->
            MissionManager.resumeMission { error ->
                cont.resume(error)
            }

            cont.invokeOnCancellation {
                // Add SDK cancel if available
            }
        }

    override suspend fun onStop(ctx: MissionHandler) { }
}

data object StopWaypointMission : MissionCommand {

    override fun validate(ctx: MissionHandler): String? = when {
        !ctx.canCreateMission() -> null
        else -> "Nothing to stop"
    }

    override suspend fun execute(ctx: MissionHandler): String? =
        suspendCancellableCoroutine { cont ->
            MissionManager.stopMission { error ->
                cont.resume(error)
            }

            cont.invokeOnCancellation {
                // Add SDK cancel if available
            }
        }

    override suspend fun onStop(ctx: MissionHandler) { }
}

data object PauseActionCommand : MissionCommand {

    override fun validate(ctx: MissionHandler): String? = when {
        !MissionActionManager.isRunning -> "Timeline not running"
        else -> null
    }

    override suspend fun execute(ctx: MissionHandler): String? {
        MissionActionManager.pause()
        return null
    }

    override suspend fun onStop(ctx: MissionHandler) { }
}

data object ResumeActionCommand : MissionCommand {

    override fun validate(ctx: MissionHandler): String? = when {
        MissionActionManager.isRunning -> "Timeline already running"
        else -> null
    }

    override suspend fun execute(ctx: MissionHandler): String? {
        MissionActionManager.resume()
        return null
    }

    override suspend fun onStop(ctx: MissionHandler) { }
}

open class ActionCommand(val action: MissionAction) : MissionCommand {

    override fun validate(ctx: MissionHandler): String? = if (MissionActionManager.isRunning) {
        "Busy"
    } else {
        null
    }

    override suspend fun execute(ctx: MissionHandler): String? =
        suspendCancellableCoroutine { cont ->
            MissionActionManager.clear()

            val error = MissionActionManager.schedule(action)

            if (error != null) {
                cont.resume("Error in $action: ${error.description}")
                return@suspendCancellableCoroutine
            }

            MissionActionManager.onFinish(action::class) {
                cont.resume(null)
            }

            MissionActionManager.startListener {
                // onError
                cont.resume("Action failed: $it")
            }

            MissionActionManager.start()

            cont.invokeOnCancellation {
                // Add SDK cancel if available
                MissionActionManager.stop()
            }
        }

    override suspend fun onStop(ctx: MissionHandler) = MissionActionManager.stop()
}

data class RepositionCommand(private val target: Coordinates3D, private val speed: Float) :
    ActionCommand(
        GoToAction(
            LocationCoordinate2D(target.lat, target.long),
            target.alt
        ).apply { flightSpeed = speed }
    )

data class ReturnToHomeCommand(private val autoConfirmLanding: Boolean) :
    ActionCommand(
        GoHomeAction().apply {
            autoConfirmLandingEnabled = autoConfirmLanding
        }
    )

data class LandCommand(private val autoConfirmLanding: Boolean) :
    ActionCommand(
        LandAction().apply {
            autoConfirmLandingEnabled = autoConfirmLanding
        }
    )
