package org.WenuLink.mavlink

import android.util.Log
import com.MAVLink.MAVLinkPacket
import com.MAVLink.Messages.MAVLinkMessage
import com.MAVLink.Parser
import com.MAVLink.enums.MAV_COMPONENT.MAV_COMP_ID_AUTOPILOT1
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

class MAVLinkClient(
    private val localPort: Int = 14550,
    private val targetIp: String,
    private val targetPort: Int = 14550,
    private val onMessageReceived: ((MAVLinkMessage) -> Unit)? = null
) {
    private val TAG: String = MAVLinkClient::class.java.simpleName
    private lateinit var socket: DatagramSocket
    private val parser = Parser()
    private var job: Job? = null
    val isRunning: AtomicBoolean = AtomicBoolean(false)
    var systemID = 1
        private set

    fun start(onResult: (Boolean, String?) -> Unit) {
        socket = DatagramSocket(localPort)
        socket.soTimeout = 100 // Set a timeout for receive
        isRunning.set(true)

        job = CoroutineScope(Dispatchers.IO).launch {
            val buffer = ByteArray(512)
            while (isRunning.get() && !socket.isClosed) {
                try {
                    // Check if data is available before receiving
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)

                    // Process the received data
                    for (byte in packet.data.take(packet.length)) {
                        val received = parser.mavlink_parse_char(byte.toInt() and 0xFF)
                        received?.let {
                            val message = it.unpack()
                            onMessageReceived?.invoke(message)
                        }
                    }
                } catch (_: SocketTimeoutException) {
                    // Silently omit
                } catch (e: SocketException) {
                    Log.i(TAG, "Socket closed with exception: ${e.message}")
                } catch (e: Exception) {
                    Log.e(TAG, "Error receiving MAVLink: ${e.message}")
                }
            }
        }
        onResult(true, null)
    }

    fun stop(onResult: (Boolean, String?) -> Unit) {
        isRunning.set(false)
        runBlocking {
            // waiting for the coroutine to finish it's work
            job?.join()
        }
        socket.close()
        socket = DatagramSocket()
        job = null // Clear the reference to allow restart
        onResult(true, null)
    }

    fun isReady(): Boolean {
        return isRunning.get()
    }

    fun sendMessage(msg: MAVLinkMessage) {
        if (socket.isClosed) {
            Log.i(TAG, "Socket closed, cannot send")
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            val packet: MAVLinkPacket = msg.pack()
            packet.sysid = systemID
            packet.compid = MAV_COMP_ID_AUTOPILOT1
            val bytes = packet.encodePacket()
            val address = InetAddress.getByName(targetIp)
            val datagram = DatagramPacket(bytes, bytes.size, address, targetPort)
            try {
                socket.send(datagram)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send: $e")
            }
        }
    }
}
