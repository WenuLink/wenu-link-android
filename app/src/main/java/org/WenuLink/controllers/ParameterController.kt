package org.WenuLink.controllers

import org.WenuLink.mavlink.MAVLinkClient
import com.MAVLink.Messages.MAVLinkMessage
import com.MAVLink.common.msg_param_request_list
import com.MAVLink.common.msg_param_request_read
import com.MAVLink.common.msg_param_set
import com.MAVLink.common.msg_param_value
import io.getstream.log.taggedLogger
import org.WenuLink.adapters.AircraftHandler
import org.WenuLink.parameters.ArduPilotParametersProvider
import org.WenuLink.parameters.DJIParametersProvider
import org.WenuLink.parameters.ParamValue
import org.WenuLink.parameters.ParameterRegistry
import org.WenuLink.parameters.ParameterSpec
import kotlin.getValue
import kotlin.math.round

/**
 * MAVLinkController class to deal with the parameters service and related MAVLink messages.
 * https://mavlink.io/en/services/parameter.html
 */
class ParameterController (
    override val client: MAVLinkClient
) : IController {

    private val logger by taggedLogger("ParameterController")
    private val registry = ParameterRegistry(listOf(
        ArduPilotParametersProvider,
        DJIParametersProvider
    ))
    var wasInitialized = false
        private set
    var wasRequested = false
        private set

    suspend fun load() {
        registry.loadParameters()
        wasInitialized = true
    }

    override fun processMessage(msg: MAVLinkMessage, aircraft: AircraftHandler): Boolean {
        var processed = true
        when (msg.msgid) {
            msg_param_request_list.MAVLINK_MSG_ID_PARAM_REQUEST_LIST -> requestList()
            msg_param_request_read.MAVLINK_MSG_ID_PARAM_REQUEST_READ -> requestRead(msg)
            msg_param_set.MAVLINK_MSG_ID_PARAM_SET -> requestUpdate(msg)
            else -> processed = false
        }
        return processed
    }

    fun requestList() {
        registry.all().forEach { param ->
            param.spec.read { value ->
                if (value != null) sendParameter(param.spec, value)
            }
        }
        wasRequested = true
        logger.d { "Parameters list requested: $wasRequested" }
    }

    fun msgParam(name: String, value: Float, type: Short): msg_param_value {
        return msg_param_value().apply {
            param_Id = name
            param_value = value
            param_type = type
            param_count = registry.size()
            param_index = 65535
        }
    }

    fun msgParam(spec: ParameterSpec, value: ParamValue): msg_param_value {
        val pValue = spec.toMavlink(value)
        val msg = msgParam(spec.name, pValue.toFloat(), spec.type.toShort())
        msg.param_index = spec.index
        return msg
    }

    fun sendParameter(spec: ParameterSpec, value: ParamValue) {
        val msg = msgParam(spec, value)
        logger.d { "Sending parameter $spec=${msg.param_value.toInt()}" }
        client.sendMessage(msg)
    }

    fun requestRead(msg: MAVLinkMessage) {
        val paramMsg = msg as msg_param_request_read
        val param = registry.getByIndex(paramMsg.param_index.toInt())
            ?: registry.getByName(paramMsg.param_Id)

        if (param == null) {
            logger.w { "Parameter not found: $paramMsg" }
            return
        }

        param.spec.read { value ->
            if (value != null) sendParameter(param.spec, value)
        }
    }

    fun requestUpdate(msg: MAVLinkMessage) {
        val paramMsg = msg as msg_param_set
        val param = registry.getByName(paramMsg.param_Id)
        if (param == null) {
            logger.w { "Parameter not found: $paramMsg" }
            return
        }

        val value = param.spec.fromMavlink(round(paramMsg.param_value).toInt())

        param.spec.write(value) { error ->
            if (error == null) {
                sendParameter(param.spec, value)
            }
        }
    }

}
