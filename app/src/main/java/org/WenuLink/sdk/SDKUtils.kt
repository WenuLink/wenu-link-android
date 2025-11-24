/**
 * based on https://github.com/dji-sdk/Mobile-SDK-Android/blob/master/Sample%20Code/app/src/main/java/com/dji/sdk/sample/internal/utils/ModuleVerificationUtil.java
 */
package org.WenuLink.sdk

import android.util.Log
import org.WenuLink.adapters.ArduCopterFlightMode
import dji.common.error.DJIError
import dji.common.error.DJISDKError
import dji.common.flightcontroller.ConnectionFailSafeBehavior
import dji.common.flightcontroller.ControlMode
import dji.common.flightcontroller.FlightMode
import dji.common.flightcontroller.GPSSignalLevel
import dji.common.flightcontroller.virtualstick.RollPitchControlMode
import dji.common.flightcontroller.virtualstick.VerticalControlMode
import dji.common.flightcontroller.virtualstick.YawControlMode
import dji.common.util.CommonCallbacks
import dji.sdk.base.BaseProduct
import dji.sdk.flightcontroller.FlightController
import dji.sdk.products.Aircraft
import dji.sdk.realname.AppActivationManager
import dji.sdk.sdkmanager.DJISDKManager


object SDKUtils {
    fun getUsbAction(): String {
        return DJISDKManager.USB_ACCESSORY_ATTACHED
    }

    fun getProductInstance(): BaseProduct? {
        return DJISDKManager.getInstance().product
    }

    fun isAircraftConnected(): Boolean {
        return getProductInstance() != null && getProductInstance() is Aircraft
    }

    fun getAircraftInstance(): Aircraft? {
        if (!isAircraftConnected()) {
            return null
        }
        return getProductInstance() as Aircraft?
    }

    fun getFlightController(): FlightController? {
        val aircraft: Aircraft? = getAircraftInstance()
        if (aircraft != null) return aircraft.flightController
        return null
    }

    fun getAppActivationManager(): AppActivationManager? {
        return DJISDKManager.getInstance().appActivationManager
    }

    fun getGPSSignalLevelArray(inputLevel: GPSSignalLevel): BooleanArray {
        // Create a boolean array with the same size as the number of enum constants
        val result = BooleanArray(GPSSignalLevel.entries.size)

        // Iterate through the enum constants and set the corresponding index to true if it matches the input level
        for (i in GPSSignalLevel.entries.indices) {
            result[i] = GPSSignalLevel.entries[i] == inputLevel
        }

        return result
    }

    fun createCompletionCallback(onResult: (String?) -> Unit): CommonCallbacks.CompletionCallback<DJIError> {
        return CommonCallbacks.CompletionCallback<DJIError> { error ->
            if (error == null) onResult(null)
            else {
                Log.e("SDKUtils", "CompletionCallback onFailure $error")
                onResult(error.description)
            }
        }
    }

    fun createCompletionCallbackBoolean(onResult: (Int?) -> Unit): CommonCallbacks.CompletionCallbackWith<Boolean> {
        return object : CommonCallbacks.CompletionCallbackWith<Boolean> {
            override fun onSuccess(result: Boolean) {
                onResult(if (result) 1 else 0)
            }

            override fun onFailure(error: DJIError) {
                val notSupported = error == DJISDKError.FEATURE_NOT_SUPPORTED
                Log.e("SDKUtils", "CompletionCallbackWith<Boolean> onFailure ${error.description} $notSupported")
                // TODO: replace with DJIError type
                if (error.toString().contains("(255)")) onResult(0) // not supported
                else onResult(null)
            }
        }
    }

    fun createCompletionCallbackInteger(onResult: (Int?) -> Unit): CommonCallbacks.CompletionCallbackWith<Int> {
        return object : CommonCallbacks.CompletionCallbackWith<Int> {
            override fun onSuccess(result: Int) {
                onResult(result)
            }

            override fun onFailure(error: DJIError) {
                Log.e("SDKUtils", "CompletionCallbackWith<Int> onFailure $error")
                onResult(null)
            }
        }
    }

    fun createCompletionCallbackSafety(onResult: (Int?) -> Unit): CommonCallbacks.CompletionCallbackWith<ConnectionFailSafeBehavior> {
        return object : CommonCallbacks.CompletionCallbackWith<ConnectionFailSafeBehavior> {
            override fun onSuccess(behavior: ConnectionFailSafeBehavior?) {
                when (behavior) {
                    ConnectionFailSafeBehavior.HOVER -> onResult(0)  // getParams().get(paramIndex).setParamValue(0f);
                    ConnectionFailSafeBehavior.LANDING -> onResult(1) // getParams().get(paramIndex).setParamValue(1f);
                    ConnectionFailSafeBehavior.GO_HOME -> onResult(2) // getParams().get(paramIndex).setParamValue(2f);
                    ConnectionFailSafeBehavior.UNKNOWN -> onResult(255)  // getParams().get(paramIndex).setParamValue(255f);
                    else -> onResult(null)
                }
            }

            override fun onFailure(error: DJIError?) {
                Log.e("SDKUtils", "createCompletionCallbackSafety onFailure $error")
                onResult(null)
            }
        }
    }

    fun int2FailSafeBehavior(value: Int): ConnectionFailSafeBehavior {
        val failSafeBehavior = ConnectionFailSafeBehavior.entries.find { it.ordinal == value }
        return failSafeBehavior ?: ConnectionFailSafeBehavior.UNKNOWN
    }

    fun createCompletionCallbackControl(onResult: (Int?) -> Unit): CommonCallbacks.CompletionCallbackWith<ControlMode> {
        return object : CommonCallbacks.CompletionCallbackWith<ControlMode> {
            override fun onSuccess(mode: ControlMode?) {
                when (mode) {
                    ControlMode.MANUAL -> onResult(0) // getParams().get(paramIndex).setParamValue(0f);
                    ControlMode.SMART -> onResult(2) // getParams().get(paramIndex).setParamValue(2f);
                    ControlMode.UNKNOWN -> onResult(255)  // getParams().get(paramIndex).setParamValue(255f);
                    else -> onResult(255)
                }
            }

            override fun onFailure(error: DJIError?) {
                Log.e("SDKUtils", "createCompletionCallbackControl onFailure $error")
                if (error == DJISDKError.COMMON_UNSUPPORTED) onResult(2)  // unsupported by default in ControlMode.SMART
                else onResult(null)
            }
        }
    }

    fun int2ControlMode(value: Int): ControlMode {
        val controlMode = ControlMode.entries.find { it.ordinal == value }
        return controlMode ?: ControlMode.UNKNOWN
    }

    fun int2RollPitchControlMode(value: Int): RollPitchControlMode {
        val mode = RollPitchControlMode.entries.find { it.ordinal == value }
        return mode ?: RollPitchControlMode.ANGLE
    }


    fun int2VerticalControlMode(value: Int): VerticalControlMode {
        val mode = VerticalControlMode.entries.find { it.ordinal == value }
        return mode ?: VerticalControlMode.VELOCITY
    }


    fun int2YawControlMode(value: Int): YawControlMode {
        val mode = YawControlMode.entries.find { it.ordinal == value }
        return mode ?: YawControlMode.ANGLE
    }

    fun dji2ArduCopterFlightMode(flightMode: FlightMode): Pair<ArduCopterFlightMode, Boolean> {
        var customMode = ArduCopterFlightMode.AUTO
        var guidedFlag = false

        when (flightMode) {
            FlightMode.MANUAL -> customMode = ArduCopterFlightMode.STABILIZE
            FlightMode.ATTI -> customMode = ArduCopterFlightMode.LOITER
//            FlightMode.ATTI_COURSE_LOCK -> {}
//            FlightMode.ATTI_HOVER -> {}
//            FlightMode.HOVER -> {}
//            FlightMode.GPS_BLAKE -> {}
//            FlightMode.GPS_ATTI -> {}
//            FlightMode.GPS_COURSE_LOCK -> {}
//            FlightMode.GPS_HOME_LOCK -> {}
//            FlightMode.GPS_HOT_POINT -> {}
//            FlightMode.ASSISTED_TAKEOFF -> {}
            FlightMode.AUTO_TAKEOFF -> {
                customMode = ArduCopterFlightMode.GUIDED
                guidedFlag = true
            }

            FlightMode.AUTO_LANDING -> {
                customMode = ArduCopterFlightMode.LAND
                guidedFlag = true
            }
//            FlightMode.ATTI_LANDING -> {}
            FlightMode.GPS_WAYPOINT -> {
                customMode = ArduCopterFlightMode.AUTO
                guidedFlag = true
            }

            FlightMode.GO_HOME -> {
                customMode = ArduCopterFlightMode.RTL
                guidedFlag = true
            }
//            FlightMode.CLICK_GO -> {}
            FlightMode.JOYSTICK -> guidedFlag = true
//            FlightMode.GPS_ATTI_WRISTBAND -> {}
//            FlightMode.CINEMATIC -> {}
//            FlightMode.ATTI_LIMITED -> {}
            FlightMode.DRAW -> guidedFlag = true
            FlightMode.GPS_FOLLOW_ME -> {
                customMode = ArduCopterFlightMode.GUIDED
                guidedFlag = true
            }

            FlightMode.ACTIVE_TRACK -> {
                customMode = ArduCopterFlightMode.GUIDED
                guidedFlag = true
            }

            FlightMode.TAP_FLY -> customMode = ArduCopterFlightMode.GUIDED
//            FlightMode.PANO -> {}
//            FlightMode.FARMING -> {}
//            FlightMode.FPV -> {}
//            FlightMode.GPS_SPORT -> {}
//            FlightMode.GPS_NOVICE -> {}
//            FlightMode.CONFIRM_LANDING -> {}
//            FlightMode.TERRAIN_FOLLOW -> {}
//            FlightMode.PALM_CONTROL -> {}
//            FlightMode.QUICK_SHOT -> {}
//            FlightMode.TRIPOD -> {}
//            FlightMode.TRACK_SPOTLIGHT -> {}
//            FlightMode.MOTORS_JUST_STARTED -> {}
//            FlightMode.DETOUR -> {}
//            FlightMode.TIME_LAPSE -> {}
//            FlightMode.POI2 -> {}
//            FlightMode.OMNI_MOVING -> {}
//            FlightMode.ADSB_AVOIDING -> {}
//            FlightMode.SMART_TRACK -> {}
//            FlightMode.MOTOR_STOP_LANDING -> {}
//            FlightMode.UNKNOWN -> {}
            else -> {}
        }
        return Pair(customMode, guidedFlag)
    }
}