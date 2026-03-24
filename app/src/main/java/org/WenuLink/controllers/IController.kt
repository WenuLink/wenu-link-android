package org.WenuLink.controllers

import com.MAVLink.Messages.MAVLinkMessage
import com.MAVLink.common.msg_command_int
import com.MAVLink.common.msg_command_long
import org.WenuLink.adapters.aircraft.AircraftHandler
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
        // TODO: centralize client sending answer messages
        return false
    }

    fun processCommandLong(commandLongMsg: msg_command_long, aircraft: AircraftHandler): Boolean =
        false

    fun processCommandInt(commandIntMsg: msg_command_int, aircraft: AircraftHandler): Boolean =
        false

    fun processRequestInt(commandIntMsg: msg_command_int, aircraft: AircraftHandler): Boolean =
        false

    fun processRequestLong(commandLongMsg: msg_command_long, aircraft: AircraftHandler): Boolean =
        false

    fun createMessage(messageID: Int, aircraft: AircraftHandler): MAVLinkMessage? = null
}
