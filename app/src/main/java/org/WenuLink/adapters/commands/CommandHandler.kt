package org.WenuLink.adapters.commands

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
    private var commandJob: Deferred<String?>? = null
    private val commandChannel =
        Channel<Pair<ICommand<T>, (String?) -> Unit>>(Channel.UNLIMITED)

    fun startCommandProcessor(scope: CoroutineScope, handler: T, logger: TaggedLogger) {
        requestJob?.cancel()

        requestJob = scope.launch {
            for ((cmd, onResult) in commandChannel) {
                logger.d { "Executing: ${cmd::class.simpleName}" }

                try {
                    val validationError = cmd.validate(handler)
                    if (validationError != null) {
                        onResult(validationError)
                        continue
                    }

                    val deferred = async {
                        cmd.execute(handler)
                    }

                    commandJob = deferred

                    val result = deferred.await()
                    onResult(result)
                } catch (e: CancellationException) {
                    logger.w { "Command cancelled: ${cmd::class.simpleName}" }
                    cmd.onStop(handler)
                    onResult(null)
                } catch (e: Exception) {
                    logger.d { "Command failed: ${cmd::class.simpleName}" }
                    onResult(e.message)
                } finally {
                    commandJob = null
                }
            }
        }
    }

    override fun registerScope(scope: CoroutineScope) { } // nothing to do

    fun dispatchCommand(cmd: ICommand<T>, onResult: (String?) -> Unit = {}) {
        val result = commandChannel.trySend(cmd to onResult)
        if (result.isFailure) {
            onResult("Failed to enqueue command: ${cmd::class.java.simpleName}")
        }
    }

    fun stopCommand(): String? {
        val job = commandJob ?: return "No running command"
        job.cancel(CancellationException("Stopped by user"))
        return null
    }

    override fun unload() {
        // Stop ongoing processes
        stopCommand()
        requestJob?.cancel()
        requestJob = null
    }
}
