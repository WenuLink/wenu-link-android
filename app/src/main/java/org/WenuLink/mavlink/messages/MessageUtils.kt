package org.WenuLink.mavlink.messages

object MessageUtils {
    fun toBoolean(param: Float): Boolean? = when (param) {
        1f -> true
        0f -> false
        else -> null
    }
}
