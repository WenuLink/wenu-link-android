package org.WenuLink.commands

interface ICommand<T : IHandler<T>> {
    fun validate(ctx: T): UnitResult

    suspend fun execute(ctx: T): UnitResult

    suspend fun onStop(ctx: T)
}
