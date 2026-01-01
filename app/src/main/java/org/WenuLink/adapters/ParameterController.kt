package org.WenuLink.adapters

import android.util.Log
import org.WenuLink.mavlink.MAVLinkClient
import org.WenuLink.mavlink.MAVLinkController
import org.WenuLink.sdk.FCManager
import org.WenuLink.sdk.SDKUtils
import com.MAVLink.Messages.MAVLinkMessage
import com.MAVLink.common.msg_param_request_list.MAVLINK_MSG_ID_PARAM_REQUEST_LIST
import com.MAVLink.common.msg_param_request_read
import com.MAVLink.common.msg_param_set
import com.MAVLink.common.msg_param_value
import com.MAVLink.enums.MAV_PARAM_TYPE
import kotlin.math.round

/**
 * MAVLinkController class to deal with the parameters service and related MAVLink messages.
 * https://mavlink.io/en/services/parameter.html
 */
class ParameterController (
    private var client: MAVLinkClient
) : MAVLinkController {
    // TODO: make available only supported features
    enum class Parameter {
        //    DJI_LED_ENABLED,
        DJI_SPIN_ENABLED,
        DJI_RADIUS_ENABLED,
        DJI_FOLLOW_ENABLED,
        DJI_TRIPOD_ENABLED,
        DJI_SMART_RTL_ENABLED,
        DJI_RTL_HEIGHT,
        DJI_MAX_HEIGHT,
        DJI_MAX_RADIUS,
        DJI_BAT_LOW,
        DJI_BAT_CRITIC,
        DJI_FAILSAFE,
        DJI_CTRL_MODE,
        DJI_ROLL_PITCH_MODE,
        DJI_VERT_MODE,
        DJI_YAW_MODE
    }

    private val TAG: String = ParameterController::class.java.simpleName
    private val flightController = FCManager.fcInstance!!

    override fun processMessage(msg: MAVLinkMessage) {
        when (msg.msgid) {
            MAVLINK_MSG_ID_PARAM_REQUEST_LIST -> sendList()

            msg_param_request_read.MAVLINK_MSG_ID_PARAM_REQUEST_READ -> read(
                msg as msg_param_request_read
            )

            msg_param_set.MAVLINK_MSG_ID_PARAM_SET -> update(
                msg as msg_param_set
            )
        }
    }

    fun sendParameter(paramID: Parameter, paramValue: Int) {
        Log.i(TAG, "Sending parameter $paramID[${paramID.ordinal}] = $paramValue")
        val msg = msg_param_value()
        msg.param_Id = paramID.toString()
        msg.param_value = paramValue.toFloat()
        msg.param_type = getType(paramID).toShort()
        msg.param_count = getCount()
        msg.param_index = paramID.ordinal
        client.sendMessage(msg)
    }

    fun sendList() {
        Parameter.entries.forEach { paramID ->
            read(paramID) { paramValue ->
                if (paramValue != null) sendParameter(paramID, paramValue)
            }
        }
    }

    fun read(paramMsg: msg_param_request_read) {
        val paramID = get(paramMsg.param_index.toInt())
        if (paramID == null) {
            Log.w(TAG, "readParameter: parameter not found (${paramMsg})")
            return
        }
        read(paramID) { paramValue ->
            if (paramValue != null) sendParameter(paramID, paramValue)
        }
    }

    fun update(paramMsg: msg_param_set) {
        val paramID = get(paramMsg.param_Id)
        if (paramID == null) {
            Log.w(TAG, "updateParameter: parameter not found (${paramMsg})")
            return
        }
        update(paramID, round(paramMsg.param_value).toInt()) { error ->
            if (error == null) {
                sendParameter(paramID, round(paramMsg.param_value).toInt())
            }
        }
    }

    private val booleanParams: List<Parameter> =
        listOf(
            Parameter.DJI_SPIN_ENABLED,
            Parameter.DJI_RADIUS_ENABLED,
            Parameter.DJI_FOLLOW_ENABLED,
            Parameter.DJI_TRIPOD_ENABLED,
            Parameter.DJI_SMART_RTL_ENABLED
        )

    private val integerParams: List<Parameter> =
        listOf(
            Parameter.DJI_RTL_HEIGHT,
            Parameter.DJI_MAX_HEIGHT,
            Parameter.DJI_MAX_RADIUS,
            Parameter.DJI_BAT_LOW,
            Parameter.DJI_BAT_CRITIC,
        )

    fun getCount(): Int {
        return Parameter.entries.size
    }

    private fun getType(paramID: Parameter): Int {
        return if (booleanParams.contains(paramID))
            MAV_PARAM_TYPE.MAV_PARAM_TYPE_UINT8
        else if (integerParams.contains(paramID))
            MAV_PARAM_TYPE.MAV_PARAM_TYPE_REAL32
        else
            MAV_PARAM_TYPE.MAV_PARAM_TYPE_INT16
    }

    private fun get(index: Int): Parameter? {
        return if (index < Parameter.entries.size) Parameter.entries[index] else null
    }

    private fun get(name: String): Parameter? {
        return Parameter.entries.find { it.name.equals(name, ignoreCase = true) }
    }

    private fun read(paramID: Parameter, onResult: (Int?) -> Unit) {
        when (paramID) {
            Parameter.DJI_SPIN_ENABLED -> flightController.getQuickSpinEnabled(
                SDKUtils.createCompletionCallbackBoolean(onResult)
            )

            Parameter.DJI_RADIUS_ENABLED -> flightController.getMaxFlightRadiusLimitationEnabled(
                SDKUtils.createCompletionCallbackBoolean(onResult)
            )

            Parameter.DJI_FOLLOW_ENABLED -> flightController.getTerrainFollowModeEnabled(
                SDKUtils.createCompletionCallbackBoolean(onResult)
            )

            Parameter.DJI_TRIPOD_ENABLED -> flightController.getTripodModeEnabled(
                SDKUtils.createCompletionCallbackBoolean(onResult)
            )

            Parameter.DJI_SMART_RTL_ENABLED -> flightController.getSmartReturnToHomeEnabled(
                SDKUtils.createCompletionCallbackBoolean(onResult)
            )

            Parameter.DJI_RTL_HEIGHT -> flightController.getGoHomeHeightInMeters(
                SDKUtils.createCompletionCallbackInteger(onResult)
            )

            Parameter.DJI_MAX_HEIGHT -> flightController.getMaxFlightHeight(
                SDKUtils.createCompletionCallbackInteger(onResult)
            )

            Parameter.DJI_MAX_RADIUS -> flightController.getMaxFlightRadius(
                SDKUtils.createCompletionCallbackInteger(onResult)
            )

            Parameter.DJI_BAT_LOW -> flightController.getLowBatteryWarningThreshold(
                SDKUtils.createCompletionCallbackInteger(onResult)
            )

            Parameter.DJI_BAT_CRITIC -> flightController.getSeriousLowBatteryWarningThreshold(
                SDKUtils.createCompletionCallbackInteger(onResult)
            )

            Parameter.DJI_FAILSAFE -> flightController.getConnectionFailSafeBehavior(
                SDKUtils.createCompletionCallbackSafety(onResult)
            )

            Parameter.DJI_CTRL_MODE -> flightController.getControlMode(
                SDKUtils.createCompletionCallbackControl(onResult)
            )

            Parameter.DJI_ROLL_PITCH_MODE -> onResult(flightController.rollPitchControlMode.ordinal)
            Parameter.DJI_VERT_MODE -> onResult(flightController.verticalControlMode.ordinal)
            Parameter.DJI_YAW_MODE -> onResult(flightController.yawControlMode.ordinal)
        }
    }

    private fun update(paramID: Parameter, paramValue: Int, onResult: (String?) -> Unit) {
        when (paramID) {
            Parameter.DJI_SPIN_ENABLED -> flightController.setAutoQuickSpinEnabled(
                paramValue > 0,
                SDKUtils.createCompletionCallback(onResult)
            )

            Parameter.DJI_RADIUS_ENABLED -> flightController.setMaxFlightRadiusLimitationEnabled(
                paramValue > 0,
                SDKUtils.createCompletionCallback(onResult)
            )

            Parameter.DJI_FOLLOW_ENABLED -> flightController.setTerrainFollowModeEnabled(
                paramValue > 0,
                SDKUtils.createCompletionCallback(onResult)
            )

            Parameter.DJI_TRIPOD_ENABLED -> flightController.setTripodModeEnabled(
                paramValue > 0,
                SDKUtils.createCompletionCallback(onResult)
            )

            Parameter.DJI_SMART_RTL_ENABLED -> flightController.setSmartReturnToHomeEnabled(
                paramValue > 0,
                SDKUtils.createCompletionCallback(onResult)
            )

            Parameter.DJI_RTL_HEIGHT -> flightController.setGoHomeHeightInMeters(
                paramValue,
                SDKUtils.createCompletionCallback(onResult)
            )

            Parameter.DJI_MAX_HEIGHT -> flightController.setMaxFlightHeight(
                paramValue,
                SDKUtils.createCompletionCallback(onResult)
            )

            Parameter.DJI_MAX_RADIUS -> flightController.setMaxFlightRadius(
                paramValue,
                SDKUtils.createCompletionCallback(onResult)
            )

            Parameter.DJI_BAT_LOW -> flightController.setLowBatteryWarningThreshold(
                paramValue,
                SDKUtils.createCompletionCallback(onResult)
            )

            Parameter.DJI_BAT_CRITIC -> flightController.setSeriousLowBatteryWarningThreshold(
                paramValue,
                SDKUtils.createCompletionCallback(onResult)
            )

            Parameter.DJI_FAILSAFE -> flightController.setConnectionFailSafeBehavior(
                SDKUtils.int2FailSafeBehavior(paramValue),
                SDKUtils.createCompletionCallback(onResult)
            )

            Parameter.DJI_CTRL_MODE -> flightController.setControlMode(
                SDKUtils.int2ControlMode(paramValue),
                SDKUtils.createCompletionCallback(onResult)
            )

            Parameter.DJI_ROLL_PITCH_MODE -> {
                flightController.setRollPitchControlMode(
                    SDKUtils.int2RollPitchControlMode(paramValue)
                )
                onResult(null)
            }

            Parameter.DJI_VERT_MODE -> {
                flightController.setVerticalControlMode(
                    SDKUtils.int2VerticalControlMode(paramValue)
                )
                onResult(null)
            }

            Parameter.DJI_YAW_MODE -> {
                flightController.setYawControlMode(SDKUtils.int2YawControlMode(paramValue))
                onResult(null)
            }
        }
    }
}
