package org.WenuLink.commands

import kotlinx.coroutines.CoroutineScope

/**
 * Interface class to implement the Command pattern through Job Handlers
 */
interface IHandler<T : IHandler<T>> {
    fun registerScope(scope: CoroutineScope)
    fun unload()
}
