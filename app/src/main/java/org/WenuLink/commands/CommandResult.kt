package org.WenuLink.commands

sealed class CommandResult<out T> {
    data class Success<T>(val value: T) : CommandResult<T>()
    data class Failure(val reason: String) : CommandResult<Nothing>()

    val isOk: Boolean
        get() = this is Success
    val hasError: Boolean
        get() = this is Failure
    val errorReason: String
        get() = (this as? Failure)?.reason ?: "errorReason accessed on Success"

    companion object {
        val ok: UnitResult = Success(Unit)
        fun error(reason: String): CommandResult<Nothing> = Failure(reason)
    }
}

typealias UnitResult = CommandResult<Unit>
