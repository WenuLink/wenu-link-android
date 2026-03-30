package org.WenuLink.controllers

import com.MAVLink.Messages.MAVLinkMessage
import com.MAVLink.common.msg_param_request_list
import com.MAVLink.common.msg_param_request_read
import com.MAVLink.common.msg_param_set
import com.MAVLink.common.msg_param_value
import io.getstream.log.taggedLogger
import kotlin.math.round
import org.WenuLink.adapters.aircraft.AircraftHandler
import org.WenuLink.mavlink.MAVLinkClient
import org.WenuLink.parameters.ParamValue
import org.WenuLink.parameters.ParameterSpec

/**
 * MAVLinkController class to deal with the parameters service and related MAVLink messages.
 * https://mavlink.io/en/services/parameter.html
 */
class ParameterController(override val client: MAVLinkClient) : IController {
    private val logger by taggedLogger(ParameterController::class.java.simpleName)
    var wasRequested = false
        private set

    override fun processMessage(msg: MAVLinkMessage, aircraft: AircraftHandler): Boolean {
        when (msg.msgid) {
            msg_param_request_list.MAVLINK_MSG_ID_PARAM_REQUEST_LIST -> requestList(aircraft)
            msg_param_request_read.MAVLINK_MSG_ID_PARAM_REQUEST_READ -> requestRead(msg, aircraft)
            msg_param_set.MAVLINK_MSG_ID_PARAM_SET -> requestUpdate(msg, aircraft)
            else -> return false
        }
        return true
    }

    fun requestList(aircraft: AircraftHandler) {
        aircraft.parameters.all().forEach { param ->
            param.spec.read { value ->
                if (value != null) sendParameter(param.spec, value, aircraft.parameters.size())
            }
        }
        wasRequested = true
        logger.d { "Parameters list requested: $wasRequested" }
    }

    fun msgParam(spec: ParameterSpec, value: ParamValue, count: Int): msg_param_value {
        val pValue = spec.toMavlink(value)
        return msg_param_value().apply {
            param_Id = spec.name
            param_value = pValue.toFloat()
            param_type = spec.type.toShort()
            param_count = count
            param_index = if (spec.index == -1) {
                65535
            } else {
                spec.index
            }
        }
    }

    fun sendParameter(spec: ParameterSpec, value: ParamValue, count: Int) {
        val msg = msgParam(spec, value, count)
        logger.d { "Sending parameter $spec=${msg.param_value.toInt()}" }
        client.sendMessage(msg)
    }

    fun requestRead(msg: MAVLinkMessage, aircraft: AircraftHandler) {
        val paramMsg = msg as msg_param_request_read
        val param = aircraft.parameters.getByIndex(paramMsg.param_index.toInt())
            ?: aircraft.parameters.getByName(paramMsg.param_Id)

        if (param == null) {
            logger.w { "Parameter not found: $paramMsg" }
            return
        }

        param.spec.read { value ->
            if (value != null) sendParameter(param.spec, value, aircraft.parameters.size())
        }
    }

    fun requestUpdate(msg: MAVLinkMessage, aircraft: AircraftHandler) {
        val paramMsg = msg as msg_param_set
        val param = aircraft.parameters.getByName(paramMsg.param_Id)
        if (param == null) {
            logger.w { "Parameter not found: $paramMsg" }
            return
        }

        val value = param.spec.fromMavlink(round(paramMsg.param_value).toInt())

        param.spec.write(value) { error ->
            if (error == null) {
                sendParameter(param.spec, value, aircraft.parameters.size())
            }
        }
    }
}
