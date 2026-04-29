package org.WenuLink.mavlink.messages

import com.MAVLink.common.msg_command_long
import com.MAVLink.common.msg_request_data_stream

/**
 * Message bindings for
 * [MAV_CMD_SET_MESSAGE_INTERVAL](https://mavlink.io/en/messages/common.html#MAV_CMD_SET_MESSAGE_INTERVAL).
 *
 * @param messageId     The MAVLink message ID
 * @param intervalUs    The interval between two messages. -1: disable. 0: request default rate
 *                      (which may be zero).
 */
data class SetMessageIntervalCommandLong(val messageId: Int, val intervalUs: Long) {
    constructor(msg: msg_command_long) : this(
        messageId = msg.param1.toInt(),
        intervalUs = msg.param2.toLong()
    )
}

/**
 * Message bindings for
 * [MAV_DATA_STREAM](https://mavlink.io/en/messages/common.html#MAV_DATA_STREAM)
 * (received via [REQUEST_DATA_STREAM](https://mavlink.io/en/messages/common.html#REQUEST_DATA_STREAM)).
 *
 * @param targetSystem      The target requested to send the message stream.
 * @param targetComponent   The target requested to send the message stream.
 * @param streamId          The ID of the requested data stream. ([com.MAVLink.enums.MAV_DATA_STREAM])
 * @param rateHz            The requested message rate
 * @param active            1 to start sending, 0 to stop sending.
 */
data class RequestDataStreamMessage(
    val targetSystem: Int,
    val targetComponent: Int,
    val streamId: Int,
    val rateHz: Int,
    val active: Boolean
) {
    constructor(msg: msg_request_data_stream) : this(
        targetSystem = msg.target_system.toInt(),
        targetComponent = msg.target_component.toInt(),
        streamId = msg.req_stream_id.toInt(),
        rateHz = msg.req_message_rate,
        active = msg.start_stop.toInt() == 1
    )

    fun toIntervalUs(): Long = when {
        !active -> -1L
        rateHz == 0 -> 1_000_000L
        else -> (1_000_000_0 / rateHz).toLong()
    }
}
