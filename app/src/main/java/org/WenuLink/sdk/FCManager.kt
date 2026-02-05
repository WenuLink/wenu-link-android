package org.WenuLink.sdk

import org.WenuLink.adapters.TelemetryData
import dji.common.error.DJIError
import dji.common.flightcontroller.FlightControllerState
import dji.common.model.LocationCoordinate2D
import dji.common.util.CommonCallbacks
import dji.sdk.flightcontroller.FlightController
import io.getstream.log.taggedLogger
import kotlinx.coroutines.delay
import org.WenuLink.adapters.Coordinates3D
import org.WenuLink.adapters.AsyncUtils
import kotlin.getValue
import kotlin.math.abs

object FCManager {
    private val logger by taggedLogger("FlightControllerManager")

    var fcInstance: FlightController? = null
        private set
    private var serialNumber: String? = null
    private var fwVersion: String? = null

    @Synchronized
    fun updateSerialNumber(sn: String) {
        serialNumber = sn
    }

    @Synchronized
    fun updateFirmwareVersion(firmware: String) {
        fwVersion = firmware
    }

    @Synchronized
    fun init(flightController: FlightController) {
        fcInstance = flightController

        flightController.getSerialNumber(object : CommonCallbacks.CompletionCallbackWith<String> {
            override fun onSuccess(sn: String) {
                updateSerialNumber(sn)
                logger.i { "${this@FCManager}: $sn" }
            }

            override fun onFailure(error: DJIError) {
                logger.e { "No Serial Number obtained: $error" }
            }

        })
        flightController.getFirmwareVersion(object : CommonCallbacks.CompletionCallbackWith<String> {
            override fun onSuccess(firmware: String) {
                updateFirmwareVersion(firmware)
                logger.i { this@FCManager.toString() }
            }

            override fun onFailure(error: DJIError) {
                logger.e { "No Firmware version obtained: $error" }
            }

        })

        logger.i { "FlightController init" }

        SimManager.init(flightController)
    }

    @Synchronized
    fun isConnected(): Boolean {
        return fcInstance != null
    }

    override fun toString(): String {
        return if (!isConnected()) {
            val sn = "SN: " + if (serialNumber != null) serialNumber else "N/A"
            val fw = "FW: " + if (fwVersion != null) fwVersion else "N/A"
            if (serialNumber != null && fwVersion != null) {
                "FlightController $sn - $fw"
            } else
                "Reading FlightController"
        } else "No FlightController"
    }

    fun state2telemetry(state: FlightControllerState): TelemetryData {
        return TelemetryData(
            roll = state.attitude.roll,
            pitch = state.attitude.pitch,
            yaw = state.attitude.yaw,
            latitude = state.aircraftLocation.latitude,
            longitude = state.aircraftLocation.longitude,
            altitude = state.aircraftLocation.altitude,
            positionX = 0F,
            positionY = 0F,
            positionZ = 0F,
            velocityX = state.velocityX,
            velocityY = state.velocityY,
            velocityZ = state.velocityZ,
            flightTime = state.flightTimeInSeconds,
            takeOffAltitude = state.takeoffLocationAltitude,
            isFlying = state.isFlying,
            motorsOn = state.areMotorsOn(),
            satelliteCount = state.satelliteCount,
            gpsLevel = SDKUtils.getGPSSignalLevelArray(state.gpsSignalLevel)
        )
    }

    fun registerStateCallback(stateCallback: (FlightControllerState) -> Unit) {
        fcInstance?.setStateCallback(stateCallback)
    }

    fun unregisterStateCallback() {
        fcInstance?.setStateCallback(null)
    }

    fun getHomePosition(): Coordinates3D? {
        val flightState = fcInstance!!.state
        if (!flightState.isHomeLocationSet) {
            return null
        }

        val homeLocation = flightState.homeLocation
        return Coordinates3D(
            homeLocation.latitude,
            homeLocation.longitude,
            flightState.goHomeHeight.toFloat()
        )
    }

    fun setHomePosition(
        latitude: Double? = null,
        longitude: Double? = null,
        onResult: (String?) -> Unit
    ) {
        if (latitude != null && longitude != null) {
            fcInstance?.setHomeLocation(
                LocationCoordinate2D(latitude, longitude),
                SDKUtils.createCompletionCallback(onResult)
            )
        } else {
            fcInstance?.setHomeLocationUsingAircraftCurrentLocation(
                SDKUtils.createCompletionCallback(onResult)
            )
        }
    }

    fun isFlying(): Boolean {
        return fcInstance?.state?.isFlying ?: false
    }

    fun needLandingConfirmation() = fcInstance?.state?.isLandingConfirmationNeeded ?: false

    fun startTakeoff() {
        //        fcInstance?.startTakeoff { SDKUtils.createCompletionCallback(onResult) }
        fcInstance?.startTakeoff { }
    }

    fun startLanding() {
//        fcInstance?.startLanding { SDKUtils.createCompletionCallback(onResult) }
        fcInstance?.startLanding { }
    }

    fun confirmLanding(onResult: (String?) -> Unit) {
        // for somehow these kind of actions does not return anything, possibly is a thread issue.
        fcInstance?.confirmLanding { SDKUtils.createCompletionCallback(onResult) }
    }

    fun areMotorsArmed(): Boolean {
        return fcInstance?.state?.areMotorsOn() ?: false
    }

    fun armMotors() {
        logger.d { "Arming motors" }
//        fcInstance?.turnOnMotors { SDKUtils.createCompletionCallback(onResult) }
        fcInstance?.turnOnMotors { } // apparently ignores the callback and must wait for change to happen
    }

    fun disarmMotors() {
        logger.d { "Disarming motors" }
//        fcInstance?.turnOffMotors { SDKUtils.createCompletionCallback(onResult) }
        fcInstance?.turnOffMotors { } // apparently ignores the callback and must wait for change to happen
    }

}
