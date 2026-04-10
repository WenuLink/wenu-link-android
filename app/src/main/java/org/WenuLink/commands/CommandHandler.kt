package org.WenuLink.commands

import io.getstream.log.TaggedLogger
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

open class CommandHandler<T : IHandler<T>> : IHandler<T> {
    private var requestJob: Job? = null
    private var commandJob: Deferred<UnitResult>? = null
    var currentCommand: ICommand<T>? = null
        private set
    private val commandChannel =
        Channel<Pair<ICommand<T>, (UnitResult) -> Unit>>(Channel.UNLIMITED)

    fun startCommandProcessor(scope: CoroutineScope, handler: T, logger: TaggedLogger) {
        requestJob?.cancel()

        requestJob = scope.launch {
            for ((cmd, onResult) in commandChannel) {
                logger.d { "Executing: ${cmd::class.simpleName}" }
                currentCommand = cmd

                try {
                    val validationResult = cmd.validate(handler)
                    if (validationResult is CommandResult.Failure) {
                        onResult(validationResult)
                        continue
                    }

                    val deferred = async {
                        cmd.execute(handler)
                    }

                    commandJob = deferred

                    val result = deferred.await()
                    onResult(result)
                } catch (_: CancellationException) {
                    logger.w { "Command cancelled: ${cmd::class.simpleName}" }
                    cmd.onStop(handler)
                    onResult(CommandResult.ok)
                } catch (e: Exception) {
                    logger.d { "Command failed: ${cmd::class.simpleName}" }
                    onResult(CommandResult.error(e.message ?: "Unknown error"))
                } finally {
                    commandJob = null
                    currentCommand = null
                }
            }
        }
    }

    override fun registerScope(scope: CoroutineScope) { } // nothing to do

    fun dispatchCommand(cmd: ICommand<T>, onResult: (UnitResult) -> Unit = {}) {
        val result = commandChannel.trySend(cmd to onResult)
        if (result.isFailure) {
            onResult(
                CommandResult.error("Failed to enqueue command: ${cmd::class.java.simpleName}")
            )
        }
    }

    fun stopCommand() = commandJob?.cancel(CancellationException("Stopped by user"))

    override fun unload() {
        // Stop ongoing processes
        stopCommand()
        requestJob?.cancel()
        requestJob = null
    }
}
