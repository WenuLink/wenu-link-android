package org.WenuLink.sdk

import org.WenuLink.adapters.TelemetryData
import dji.common.error.DJIError
import dji.common.flightcontroller.CompassCalibrationState
import dji.common.flightcontroller.FlightControllerState
import dji.common.flightcontroller.imu.SensorState
import dji.common.model.LocationCoordinate2D
import dji.common.util.CommonCallbacks
import dji.sdk.flightcontroller.FlightController
import io.getstream.log.taggedLogger
import org.WenuLink.adapters.Coordinates3D
import org.WenuLink.adapters.SensorState as AppSensorState
import org.WenuLink.adapters.IMUState as AppIMUState
import kotlin.getValue

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
        fcInstance?.startTakeoff { }
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
        fcInstance?.turnOnMotors { } // ignored callback, async wait for state change
    }

    fun disarmMotors() {
        logger.d { "Disarming motors" }
        fcInstance?.turnOffMotors { } // same than armMotors, async wait required
    }

    fun sensorState(sensorState: SensorState?): AppSensorState {
        return when (sensorState) {
            SensorState.DISCONNECTED,
            SensorState.CALIBRATING,
            SensorState.CALIBRATION_FAILED,
            SensorState.WARMING_UP
                -> AppSensorState.BOOT

            SensorState.DATA_EXCEPTION,
            SensorState.IN_MOTION,
            SensorState.LARGE_BIAS
                -> AppSensorState.CALIBRATION_NEEDED

            SensorState.NORMAL_BIAS,
            SensorState.MEDIUM_BIAS,
            SensorState.UNKNOWN
                -> AppSensorState.OK

            null -> AppSensorState.BOOT
        }
    }


    fun registerIMUState(onSensor: (AppIMUState) -> Unit) {
        val nSensors = fcInstance?.imuCount ?: 0
        logger.d { "Listening sensor: $nSensors IMU(s)" }
        val state = AppIMUState()
        if (nSensors > 0) fcInstance?.setIMUStateCallback { p0 ->
            // The callback is executed one time per sensor, with -1 indicating the list's end
            if (p0.index != -1) {
                // assumes same number gyros and accel
                if (state.gyroscope.getOrNull(p0.index) == null)
                    state.gyroscope.add(sensorState(p0.gyroscopeState))
                else
                    state.gyroscope[p0.index] = sensorState(p0.gyroscopeState)

                if (state.accelerometer.getOrNull(p0.index) == null)
                    state.accelerometer.add(sensorState(p0.accelerometerState))
                else
                    state.accelerometer[p0.index] = sensorState(p0.accelerometerState)
            } else {
                // Publish a copy after receiving the entire list
                onSensor(
                    AppIMUState().copy(
                        gyroscope = state.gyroscope,
                        accelerometer = state.accelerometer
                    )
                )
            }
        }
        else logger.i { "No IMU sensor found!" }
    }

    fun unregisterIMUState() {
        logger.d { "Stop listening sensor: ${fcInstance?.imuCount} IMU(s)" }
        if ((fcInstance?.imuCount ?: 0) > 0)
            fcInstance?.setIMUStateCallback(null)
    }

    fun compassOk(): Boolean {
        val nSensors = fcInstance?.compassCount ?: 0
        var compassOk = false
        logger.d { "Reading sensor: $nSensors compasses" }
        if (nSensors > 0) {
            val hasError = fcInstance?.compass?.hasError() ?: true
            val calState = fcInstance?.compass?.calibrationState
            compassOk = !hasError && calState == CompassCalibrationState.NOT_CALIBRATING
        } else logger.i { "No Compass sensor found!" }

        return compassOk
    }

}
