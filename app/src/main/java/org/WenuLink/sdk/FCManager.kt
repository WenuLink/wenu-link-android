package org.WenuLink.sdk

import org.WenuLink.adapters.TelemetryData
import dji.common.error.DJIError
import dji.common.flightcontroller.FlightControllerState
import dji.common.model.LocationCoordinate2D
import dji.common.util.CommonCallbacks
import dji.sdk.flightcontroller.FlightController
import io.getstream.log.taggedLogger
import kotlin.getValue

object FCManager {
    private val logger by taggedLogger("FlightControllerManager")

    var fcInstance: FlightController? = null
        private set
    @Volatile private var serialNumber: String? = null
    @Volatile private var fwVersion: String? = null

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
        // TODO: move dji2ArduCopterFlightMode to adapters package to maintain SDK code isolated
        val (customMode, guidedFlag) = SDKUtils.dji2ArduCopterFlightMode(state.flightMode)
        return TelemetryData(
            roll = state.attitude.roll,
            pitch = state.attitude.pitch,
            yaw = state.attitude.yaw,
            latitude = state.aircraftLocation.latitude,
            longitude = state.aircraftLocation.longitude,
            altitude = state.aircraftLocation.altitude,
            velocityX = state.velocityX,
            velocityY = state.velocityY,
            velocityZ = state.velocityZ,
            flightTime = state.flightTimeInSeconds,
            flightMode = customMode,
            flightGuided = guidedFlag,
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

    fun getHomePosition(): Triple<Double, Double, Int>? {
        val flightState = fcInstance!!.state
        if (!flightState.isHomeLocationSet) {
            return null
        }

        val homeLocation = flightState.homeLocation
        return Triple(
            first = homeLocation.latitude,
            second = homeLocation.longitude,
            third = flightState.goHomeHeight
        )
    }

    fun getCurrentLocation(): Pair<Double?, Double?> {
        return Pair(fcInstance?.state?.aircraftLocation?.latitude,
            fcInstance?.state?.aircraftLocation?.longitude)
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

    fun simpleTakeoff(onResult: (String?) -> Unit) {
        fcInstance?.startTakeoff { SDKUtils.createCompletionCallback(onResult) }
    }

    fun simpleLanding(onResult: (String?) -> Unit) {
        fcInstance?.startLanding { SDKUtils.createCompletionCallback(onResult) }
    }

}
