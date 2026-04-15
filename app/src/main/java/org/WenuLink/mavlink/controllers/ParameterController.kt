package org.WenuLink.mavlink.controllers

import com.MAVLink.Messages.MAVLinkMessage
import com.MAVLink.common.msg_param_request_list
import com.MAVLink.common.msg_param_request_read
import com.MAVLink.common.msg_param_set
import com.MAVLink.common.msg_param_value
import io.getstream.log.taggedLogger
import kotlin.math.roundToInt
import org.WenuLink.adapters.WenuLinkHandler
import org.WenuLink.mavlink.MAVLinkClient
import org.WenuLink.parameters.ParamValue
import org.WenuLink.parameters.ParameterSpec

/**
 * MAVLinkController class to deal with the parameters service and related MAVLink messages.
 * https://mavlink.io/en/services/parameter.html
 */
class ParameterController(
    override val client: MAVLinkClient,
    override val handler: WenuLinkHandler
) : IController {
    private val logger by taggedLogger(ParameterController::class.java.simpleName)

    var wasRequested = false
        private set

    override fun processMessage(msg: MAVLinkMessage): Boolean {
        when (msg.msgid) {
            msg_param_request_list.MAVLINK_MSG_ID_PARAM_REQUEST_LIST -> requestList()
            msg_param_request_read.MAVLINK_MSG_ID_PARAM_REQUEST_READ -> requestRead(msg)
            msg_param_set.MAVLINK_MSG_ID_PARAM_SET -> requestUpdate(msg)
            else -> return false
        }
        return true
    }

    fun requestList() {
        handler.aircraft.parameters.all().forEach { param ->
            param.spec.read { value ->
                if (value != null) {
                    sendParameter(param.spec, value)
                }
            }
        }
        wasRequested = true
        logger.d { "Parameters list requested: $wasRequested" }
    }

    private fun msgParam(spec: ParameterSpec, value: ParamValue): msg_param_value {
        val pValue = spec.toMavlink(value)
        return msg_param_value().apply {
            param_Id = spec.name
            param_value = pValue.toFloat()
            param_type = spec.type.toShort()
            param_count = handler.aircraft.parameters.size()
            param_index = if (spec.index == -1) {
                65535
            } else {
                spec.index
            }
        }
    }

    fun sendParameter(spec: ParameterSpec, value: ParamValue) {
        val msg = msgParam(spec, value)
        logger.d { "Sending parameter $spec=${msg.param_value.toInt()}" }
        client.sendMessage(msg)
    }

    fun requestRead(msg: MAVLinkMessage) {
        val paramMsg = msg as msg_param_request_read
        val param = handler.aircraft.parameters.getByIndex(paramMsg.param_index.toInt())
            ?: handler.aircraft.parameters.getByName(paramMsg.param_Id)

        if (param == null) {
            logger.w { "Parameter not found: $paramMsg" }
            return
        }

        param.spec.read { value ->
            if (value != null) {
                sendParameter(param.spec, value)
            }
        }
    }

    fun requestUpdate(msg: MAVLinkMessage) {
        val paramMsg = msg as msg_param_set
        val param = handler.aircraft.parameters.getByName(paramMsg.param_Id)
        if (param == null) {
            logger.w { "Parameter not found: $paramMsg" }
            return
        }

        val value = param.spec.fromMavlink(paramMsg.param_value.roundToInt())

        param.spec.write(value) { error ->
            if (error == null) {
                sendParameter(param.spec, value)
            }
        }
    }
}
