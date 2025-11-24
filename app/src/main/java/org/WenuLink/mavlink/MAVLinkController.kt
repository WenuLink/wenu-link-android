package org.WenuLink.mavlink

import com.MAVLink.Messages.MAVLinkMessage

/**
 * Interface class to deal with MAVLink messages from the different services available.
 * Each MAVLink microservice should inherit this class and implement the logic to deal with the
 * corresponding messages.
 *
 * https://mavlink.io/en/services/
 */
interface MAVLinkController {
    fun processMessage(msg: MAVLinkMessage) {}
}