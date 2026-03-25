package org.WenuLink.adapters.commands

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

class CommandProcessor<T : IHandler<T>>(private val handler: T) {
    private var requestJob: Job? = null
    private var commandJob: Deferred<String?>? = null
    private val commandChannel =
        Channel<Pair<ICommand<T>, (String?) -> Unit>>(Channel.UNLIMITED)


    fun start(scope: CoroutineScope) {
        requestJob?.cancel()

        requestJob = scope.launch {
            for ((cmd, onResult) in commandChannel) {
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
                    cmd.onStop(handler)
                    onResult(null)
                } catch (e: Exception) {
                    onResult(e.message)
                } finally {
                    commandJob = null
                }
            }
        }
    }

    fun dispatch(cmd: ICommand<T>, onResult: (String?) -> Unit = {}) {
        val result = commandChannel.trySend(cmd to onResult)
        if (result.isFailure) {
            onResult("Failed to enqueue command")
        }
    }

    fun cancel(): String? {
        val job = commandJob ?: return "No running command"
        job.cancel(CancellationException("Stopped by user"))
        return null
    }

    fun stop() {
        // Stop ongoing processes
        requestJob?.cancel()
        requestJob = null
    }
}
