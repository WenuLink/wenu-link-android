package org.WenuLink.mavlink

import com.MAVLink.Messages.MAVLinkMessage

interface MAVLinkController {
    fun processMessage(msg: MAVLinkMessage) {}
}