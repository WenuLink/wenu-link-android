package org.WenuLink.mavlink.controllers

import com.MAVLink.Messages.MAVLinkMessage
import com.MAVLink.common.msg_command_int
import com.MAVLink.common.msg_command_long
import org.WenuLink.adapters.WenuLinkHandler
import org.WenuLink.mavlink.MAVLinkClient

/**
 * Interface class to deal with MAVLink messages from the different services available.
 * Each MAVLink microservice should inherit this class and implement the logic to deal with the
 * corresponding messages.
 *
 * https://mavlink.io/en/services/
 */
interface IController {
    val client: MAVLinkClient

    fun processMessage(msg: MAVLinkMessage, handler: WenuLinkHandler): Boolean {
        // TODO: centralize client sending answer messages
        return false
    }

    fun processCommandLong(commandLongMsg: msg_command_long, handler: WenuLinkHandler): Boolean =
        false

    fun processCommandInt(commandIntMsg: msg_command_int, handler: WenuLinkHandler): Boolean = false

    fun processRequestInt(commandIntMsg: msg_command_int, handler: WenuLinkHandler): Boolean = false

    fun processRequestLong(commandLongMsg: msg_command_long, handler: WenuLinkHandler): Boolean =
        false

    fun createMessage(messageID: Int, handler: WenuLinkHandler): MAVLinkMessage? = null
}
