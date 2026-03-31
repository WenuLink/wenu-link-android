package org.WenuLink.commands

interface ICommand<T : IHandler<T>> {
    fun validate(ctx: T): String?
    suspend fun execute(ctx: T): String?
    suspend fun onStop(ctx: T)
}
