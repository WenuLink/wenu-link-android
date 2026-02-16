package org.WenuLink.controllers

import com.MAVLink.Messages.MAVLinkMessage
import com.MAVLink.common.msg_command_int
import com.MAVLink.common.msg_command_long
import org.WenuLink.adapters.AircraftHandler
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

    fun processMessage(msg: MAVLinkMessage, aircraft: AircraftHandler): Boolean {
        return false
    }

    fun processCommandLong(commandLongMsg: msg_command_long, aircraft: AircraftHandler): Boolean {
        return false
    }

    fun processCommandInt(commandIntMsg: msg_command_int, aircraft: AircraftHandler): Boolean {
        return false
    }

    fun processRequestInt(commandIntMsg: msg_command_int, aircraft: AircraftHandler): Boolean {
        return false
    }

    fun processRequestLong(commandLongMsg: msg_command_long, aircraft: AircraftHandler): Boolean {
        return false
    }

    fun createMessage(messageID: Int, aircraft: AircraftHandler): MAVLinkMessage? {
        return null
    }
}