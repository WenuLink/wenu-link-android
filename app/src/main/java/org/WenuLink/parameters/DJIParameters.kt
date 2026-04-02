package org.WenuLink.parameters

import com.MAVLink.enums.MAV_PARAM_TYPE
import dji.common.error.DJIError
import dji.common.error.DJISDKError
import dji.common.flightcontroller.ConnectionFailSafeBehavior
import dji.common.flightcontroller.ControlMode
import dji.common.flightcontroller.virtualstick.RollPitchControlMode
import dji.common.flightcontroller.virtualstick.VerticalControlMode
import dji.common.flightcontroller.virtualstick.YawControlMode
import dji.common.util.CommonCallbacks
import dji.sdk.flightcontroller.FlightController

abstract class DJIParameter(name: String, type: Int, semantic: SemanticType) :
    ParameterSpec(name, type, semantic) {
    /* ---------- Shared helpers ---------- */
    protected fun completionResult(error: DJIError?, onResult: (String?) -> Unit) =
        onResult(error?.description)
}

class DJIBooleanParameter(
    name: String,
    private val getter: (CommonCallbacks.CompletionCallbackWith<Boolean>) -> Unit,
    private val setter: (Boolean, CommonCallbacks.CompletionCallback<DJIError>) -> Unit
) : DJIParameter(
    name,
    MAV_PARAM_TYPE.MAV_PARAM_TYPE_UINT8,
    SemanticType.BOOL
) {
    override fun read(onResult: (ParamValue?) -> Unit) {
        getter(object : CommonCallbacks.CompletionCallbackWith<Boolean> {
            override fun onSuccess(value: Boolean?) {
                onResult(value?.let { ParamValue.BoolVal(it) })
            }
            override fun onFailure(error: DJIError) {
                onResult(null)
            }
        })
    }

    override fun write(value: ParamValue, onResult: (String?) -> Unit) = setter(
        (value as ParamValue.BoolVal).v,
        CommonCallbacks.CompletionCallback { error ->
            onResult(error?.description)
        }
    )
}

class DJIIntParameter(
    name: String,
    private val getter: (CommonCallbacks.CompletionCallbackWith<Int>) -> Unit,
    private val setter: (Int, CommonCallbacks.CompletionCallback<DJIError>) -> Unit
) : DJIParameter(
    name,
    MAV_PARAM_TYPE.MAV_PARAM_TYPE_INT32,
    SemanticType.INT
) {
    override fun read(onResult: (ParamValue?) -> Unit) {
        getter(object : CommonCallbacks.CompletionCallbackWith<Int> {
            override fun onSuccess(value: Int?) {
                onResult(value?.let { ParamValue.IntVal(it) })
            }

            override fun onFailure(error: DJIError) {
                onResult(null)
            }
        })
    }

    override fun write(value: ParamValue, onResult: (String?) -> Unit) {
        val v = (value as ParamValue.IntVal).v
        setter(v) { err -> completionResult(err, onResult) }
    }
}

class DJIFailSafeParameter(
    name: String,
    private val getter: (
        CommonCallbacks.CompletionCallbackWith<ConnectionFailSafeBehavior>
    ) -> Unit,
    private val setter: (
        ConnectionFailSafeBehavior,
        CommonCallbacks.CompletionCallback<DJIError>
    ) -> Unit
) : DJIParameter(
    name,
    MAV_PARAM_TYPE.MAV_PARAM_TYPE_INT32,
    SemanticType.ENUM
) {
    private fun enumToInt(v: ConnectionFailSafeBehavior): Int = when (v) {
        ConnectionFailSafeBehavior.HOVER -> 0
        ConnectionFailSafeBehavior.LANDING -> 1
        ConnectionFailSafeBehavior.GO_HOME -> 2
        ConnectionFailSafeBehavior.UNKNOWN -> 255
    }

    private fun intToEnum(v: Int): ConnectionFailSafeBehavior = ConnectionFailSafeBehavior.entries
        .find { it.ordinal == v }
        ?: ConnectionFailSafeBehavior.UNKNOWN

    override fun read(onResult: (ParamValue?) -> Unit) {
        getter(object : CommonCallbacks.CompletionCallbackWith<ConnectionFailSafeBehavior> {
            override fun onSuccess(value: ConnectionFailSafeBehavior?) {
                onResult(value?.let { ParamValue.EnumVal(enumToInt(it)) })
            }

            override fun onFailure(error: DJIError?) {
                onResult(null)
            }
        })
    }

    override fun write(value: ParamValue, onResult: (String?) -> Unit) {
        val v = (value as ParamValue.EnumVal).v
        setter(intToEnum(v)) { err -> completionResult(err, onResult) }
    }
}

class DJIControlModeParameter(
    name: String,
    private val getter: (CommonCallbacks.CompletionCallbackWith<ControlMode>) -> Unit,
    private val setter: (ControlMode, CommonCallbacks.CompletionCallback<DJIError>) -> Unit
) : DJIParameter(
    name,
    MAV_PARAM_TYPE.MAV_PARAM_TYPE_INT32,
    SemanticType.ENUM
) {
    private fun enumToInt(v: ControlMode): Int = when (v) {
        ControlMode.MANUAL -> 0
        ControlMode.SMART -> 2
        ControlMode.UNKNOWN -> 255
    }

    private fun intToEnum(v: Int): ControlMode =
        ControlMode.entries.find { it.ordinal == v } ?: ControlMode.UNKNOWN

    override fun read(onResult: (ParamValue?) -> Unit) {
        getter(object : CommonCallbacks.CompletionCallbackWith<ControlMode> {
            override fun onSuccess(mode: ControlMode?) {
                onResult(mode?.let { ParamValue.EnumVal(enumToInt(it)) })
            }

            override fun onFailure(error: DJIError?) {
                if (error == DJISDKError.COMMON_UNSUPPORTED) {
                    onResult(ParamValue.EnumVal(2)) // SMART default
                } else {
                    onResult(null)
                }
            }
        })
    }

    override fun write(value: ParamValue, onResult: (String?) -> Unit) {
        val v = (value as ParamValue.EnumVal).v
        setter(intToEnum(v)) { err -> completionResult(err, onResult) }
    }
}

class DJIRollPitchControlModeParameter(
    name: String,
    private val getter: ((Int?) -> Unit) -> Unit,
    private val setter: (RollPitchControlMode, CommonCallbacks.CompletionCallback<DJIError>) -> Unit
) : DJIParameter(
    name,
    MAV_PARAM_TYPE.MAV_PARAM_TYPE_INT32,
    SemanticType.ENUM
) {
    fun intToEnum(value: Int): RollPitchControlMode {
        val mode = RollPitchControlMode.entries.find { it.ordinal == value }
        return mode ?: RollPitchControlMode.ANGLE
    }

    override fun read(onResult: (ParamValue?) -> Unit) {
        getter { v ->
            onResult(v?.let { ParamValue.EnumVal(it) })
        }
    }

    override fun write(value: ParamValue, onResult: (String?) -> Unit) {
        val v = (value as ParamValue.EnumVal).v
        setter(intToEnum(v)) { err -> completionResult(err, onResult) }
    }
}

class DJIVerticalControlModeParameter(
    name: String,
    private val getter: ((Int?) -> Unit) -> Unit,
    private val setter: (VerticalControlMode, CommonCallbacks.CompletionCallback<DJIError>) -> Unit
) : DJIParameter(
    name,
    MAV_PARAM_TYPE.MAV_PARAM_TYPE_INT32,
    SemanticType.ENUM
) {
    fun intToEnum(value: Int): VerticalControlMode {
        val mode = VerticalControlMode.entries.find { it.ordinal == value }
        return mode ?: VerticalControlMode.VELOCITY
    }

    override fun read(onResult: (ParamValue?) -> Unit) {
        getter { v ->
            onResult(v?.let { ParamValue.EnumVal(it) })
        }
    }

    override fun write(value: ParamValue, onResult: (String?) -> Unit) {
        val v = (value as ParamValue.EnumVal).v
        setter(intToEnum(v)) { err -> completionResult(err, onResult) }
    }
}

class DJIYawModeControlModeParameter(
    name: String,
    private val getter: ((Int?) -> Unit) -> Unit,
    private val setter: (YawControlMode, CommonCallbacks.CompletionCallback<DJIError>) -> Unit
) : DJIParameter(
    name,
    MAV_PARAM_TYPE.MAV_PARAM_TYPE_INT32,
    SemanticType.ENUM
) {
    fun intToEnum(value: Int): YawControlMode {
        val mode = YawControlMode.entries.find { it.ordinal == value }
        return mode ?: YawControlMode.ANGLE
    }

    override fun read(onResult: (ParamValue?) -> Unit) {
        getter { v ->
            onResult(v?.let { ParamValue.EnumVal(it) })
        }
    }

    override fun write(value: ParamValue, onResult: (String?) -> Unit) {
        val v = (value as ParamValue.EnumVal).v
        setter(intToEnum(v)) { err -> completionResult(err, onResult) }
    }
}

/**
 * DJI available provider class
 */

class DJIParametersProvider(private val fc: FlightController) : ParameterProvider {

    override fun provide(): List<ParameterSpec> = listOf(
        DJIBooleanParameter(
            "DJI_SPIN_ENABLED",
            { cb -> fc.getQuickSpinEnabled(cb) },
            { v, cb -> fc.setAutoQuickSpinEnabled(v, cb) }
        ),
        DJIBooleanParameter(
            "DJI_RADIUS_ENABLED",
            { cb -> fc.getMaxFlightRadiusLimitationEnabled(cb) },
            { v, cb -> fc.setMaxFlightRadiusLimitationEnabled(v, cb) }
        ),
        DJIBooleanParameter(
            "DJI_FOLLOW_ENABLED",
            { cb -> fc.getTerrainFollowModeEnabled(cb) },
            { v, cb -> fc.setTerrainFollowModeEnabled(v, cb) }
        ),
        DJIBooleanParameter(
            "DJI_TRIPOD_ENABLED",
            { cb -> fc.getTripodModeEnabled(cb) },
            { v, cb -> fc.setTripodModeEnabled(v, cb) }
        ),
        DJIBooleanParameter(
            "DJI_SMART_RTL_ENABLED",
            { cb -> fc.getSmartReturnToHomeEnabled(cb) },
            { v, cb -> fc.setSmartReturnToHomeEnabled(v, cb) }
        ),
        DJIIntParameter(
            "DJI_RTL_HEIGHT",
            { cb -> fc.getGoHomeHeightInMeters(cb) },
            { v, cb -> fc.setGoHomeHeightInMeters(v, cb) }
        ),
        DJIIntParameter(
            "DJI_MAX_HEIGHT",
            { cb -> fc.getMaxFlightHeight(cb) },
            { v, cb -> fc.setMaxFlightHeight(v, cb) }
        ),
        DJIIntParameter(
            "DJI_MAX_RADIUS",
            { cb -> fc.getMaxFlightRadius(cb) },
            { v, cb -> fc.setMaxFlightRadius(v, cb) }
        ),
        DJIIntParameter(
            "DJI_BAT_LOW",
            { cb -> fc.getLowBatteryWarningThreshold(cb) },
            { v, cb -> fc.setLowBatteryWarningThreshold(v, cb) }
        ),
        DJIIntParameter(
            "DJI_BAT_CRITIC",
            { cb -> fc.getSeriousLowBatteryWarningThreshold(cb) },
            { v, cb -> fc.setSeriousLowBatteryWarningThreshold(v, cb) }
        ),
        DJIFailSafeParameter(
            "DJI_FAILSAFE",
            { cb -> fc.getConnectionFailSafeBehavior(cb) },
            { v, cb -> fc.setConnectionFailSafeBehavior(v, cb) }
        ),
        DJIControlModeParameter(
            "DJI_CTRL_MODE",
            { cb -> fc.getControlMode(cb) },
            { v, cb -> fc.setControlMode(v, cb) }
        ),
        DJIRollPitchControlModeParameter(
            "DJI_ROLL_PITCH_MODE",
            { cb -> cb(fc.rollPitchControlMode.ordinal) },
            { v, cb -> fc.setRollPitchControlMode(v) }
        ),
        DJIVerticalControlModeParameter(
            "DJI_VERT_MODE",
            { cb -> cb(fc.verticalControlMode.ordinal) },
            { v, cb -> fc.setVerticalControlMode(v) }
        ),
        DJIYawModeControlModeParameter(
            "DJI_YAW_MODE",
            { cb -> cb(fc.yawControlMode.ordinal) },
            { v, cb -> fc.setYawControlMode(v) }
        )
    )
}
