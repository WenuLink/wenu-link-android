package org.WenuLink.mavlink

import com.MAVLink.MAVLinkPacket
import com.MAVLink.Messages.MAVLinkMessage
import com.MAVLink.Parser
import com.MAVLink.enums.MAV_COMPONENT.MAV_COMP_ID_AUTOPILOT1
import io.getstream.log.taggedLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

class MAVLinkClient(
    private val targetIp: String,
    private val targetPort: Int = 14550,
    private val localPort: Int = 14550,
) {
    private val logger by taggedLogger(MAVLinkClient::class.java.simpleName)
    private var socket: DatagramSocket = DatagramSocket(localPort)
    private val mavlinkParser = Parser()
    private val clientScope = CoroutineScope(Dispatchers.IO)
    val mustReceiveMessages: AtomicBoolean = AtomicBoolean(false)
    val mustSendMessages: AtomicBoolean = AtomicBoolean(false)
    var systemID = 1
        private set
    var gcsID = 255
        private set

    init {
        socket.soTimeout = 100 // Set a timeout for receive
    }

    fun closeSocket() = socket.close()

    fun stopListening() = mustReceiveMessages.set(false)

    fun startListening(onMessageReceived: ((MAVLinkMessage) -> Unit)) {
//        createSocket()
        mustReceiveMessages.set(true)
        val buffer = ByteArray(512)
        logger.d { "Listening for messages" }
        while (mustReceiveMessages.get() && !socket.isClosed) {
            try {
                // Check if data is available before receiving
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)

                // Process the received data
                for (byte in packet.data.take(packet.length)) {
                    val received = mavlinkParser.mavlink_parse_char(byte.toInt() and 0xFF)
                    received?.let {
                        val message = it.unpack()
                        onMessageReceived.invoke(message)
                    }
                }
            } catch (_: SocketTimeoutException) {
                // Silently omit
            } catch (e: SocketException) {
                logger.w { "Socket closed with exception: ${e.message}" }
            } catch (e: Exception) {
                logger.e { "Error receiving MAVLink: ${e.message}" }
            }
        }
        logger.d { "Listening end" }
    }

    fun stopSending() = mustSendMessages.set(false)

    suspend fun startSending(intervalTime: Long, sendMessagesFunction: () -> Unit) {
        logger.d { "Calling sendMessagesFunction every $intervalTime ms" }
        mustSendMessages.set(true)
        while (mustSendMessages.get() && !socket.isClosed) {
            try {
                sendMessagesFunction()
            } catch (e: Exception) {
                logger.e { "Error in outgoingMessages $e" }
            } finally {
                delay(intervalTime)
            }
        }
        logger.d { "Sending end" }
    }

    fun mustProcessMessages(): Boolean {
        return mustReceiveMessages.get() || mustSendMessages.get()
    }

    fun mustStart(): Boolean {
        return mustProcessMessages() && !socket.isClosed
    }

    fun sendMessage(msg: MAVLinkMessage) {
        if (socket.isClosed) {
            logger.i { "Socket closed, cannot send" }
            return
        }
        clientScope.launch {
            val packet: MAVLinkPacket = msg.pack()
            packet.sysid = systemID
            packet.compid = MAV_COMP_ID_AUTOPILOT1
            packet.isMavlink2 = true  // force mavlink2 protocol
            val bytes = packet.encodePacket()
            val address = InetAddress.getByName(targetIp)
            val datagram = DatagramPacket(bytes, bytes.size, address, targetPort)
            try {
                socket.send(datagram)
            } catch (e: Exception) {
                logger.e { "Failed to send: ${e.message}" }
            }
        }
    }

    fun isTargetSystem(targetID: Short): Boolean {
        return systemID.toShort() == targetID
    }
}
