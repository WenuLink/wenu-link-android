package org.WenuLink.controllers

import com.MAVLink.Messages.MAVLinkMessage
import com.MAVLink.common.msg_command_long
import kotlinx.coroutines.CoroutineScope
import org.WenuLink.adapters.AircraftHandler
import org.WenuLink.adapters.TelemetryHandler
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

    fun processCommand(commandMsg: msg_command_long, aircraft: AircraftHandler, serviceScope: CoroutineScope): Boolean {
        return false
    }

    fun processRequest(commandMsg: msg_command_long, aircraft: AircraftHandler): Boolean {
        return false
    }

    fun createMessage(messageID: Int, telemetry: TelemetryHandler, aircraft: AircraftHandler): MAVLinkMessage? {
        return null
    }
}