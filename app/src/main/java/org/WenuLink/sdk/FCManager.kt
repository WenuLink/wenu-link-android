package org.WenuLink.sdk

import dji.common.flightcontroller.CompassCalibrationState
import dji.common.flightcontroller.FlightControllerState
import dji.common.flightcontroller.imu.IMUState
import dji.common.flightcontroller.imu.SensorState
import dji.common.model.LocationCoordinate2D
import dji.sdk.flightcontroller.FlightController
import io.getstream.log.taggedLogger
import org.WenuLink.adapters.aircraft.Coordinates3D
import org.WenuLink.adapters.aircraft.GPSMapper
import org.WenuLink.adapters.aircraft.SensorState as AppSensorState
import org.WenuLink.adapters.aircraft.TelemetryData

object FCManager {
    private val logger by taggedLogger(FCManager::class.java.simpleName)

    var mInstance: FlightController? = null
        private set
    private var serialNumber: String? = null
    private var fwVersion: String? = null

    @Synchronized
    fun init(flightController: FlightController) {
        SimManager.init(flightController)
        mInstance = flightController
        logger.i { "FlightController init" }
    }

    suspend fun retrieveMetadata() = mInstance?.let { fcInstance ->
        fwVersion = SDKUtils.retrieveFirmwareVersion(fcInstance)
        serialNumber = SDKUtils.retrieveSerialNumber(fcInstance)
    }

    @Synchronized
    fun isConnected() = mInstance != null

    override fun toString(): String =
        if (isConnected() && serialNumber != null && fwVersion != null) {
            "FlightController SN: $serialNumber - FW: $fwVersion"
        } else if (isConnected()) {
            "Reading FlightController"
        } else {
            "No FlightController"
        }

    fun state2Telemetry(state: FlightControllerState): TelemetryData = TelemetryData(
        roll = state.attitude.roll,
        pitch = state.attitude.pitch,
        yaw = state.attitude.yaw,
        latitude = state.aircraftLocation.latitude,
        longitude = state.aircraftLocation.longitude,
        positionX = 0f,
        positionY = 0f,
        positionZ = 0f,
        velocityX = state.velocityX,
        velocityY = state.velocityY,
        velocityZ = state.velocityZ,
        flightTime = state.flightTimeInSeconds,
        takeOffAltitude = state.takeoffLocationAltitude,
        relativeAltitude = state.aircraftLocation.altitude,
        isFlying = state.isFlying,
        motorsOn = state.areMotorsOn(),
        satelliteCount = state.satelliteCount,
        gpsFixType = GPSMapper.toMavlinkFixType(state.gpsSignalLevel)
    )

    fun registerStateCallback(stateCallback: (FlightControllerState) -> Unit) =
        mInstance?.setStateCallback(stateCallback)

    fun unregisterStateCallback() = mInstance?.setStateCallback(null)

    fun getHomePosition(): Coordinates3D? {
        val flightState = mInstance!!.state
        if (!flightState.isHomeLocationSet) return null

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
            mInstance?.setHomeLocation(
                LocationCoordinate2D(latitude, longitude),
                SDKUtils.createCompletionCallback(onResult)
            )
        } else {
            mInstance?.setHomeLocationUsingAircraftCurrentLocation(
                SDKUtils.createCompletionCallback(onResult)
            )
        }
    }

    fun needLandingConfirmation() = mInstance?.state?.isLandingConfirmationNeeded == true

    fun getAltitude(): Float = mInstance?.state?.aircraftLocation?.altitude ?: Float.MAX_VALUE

    fun startTakeoff(onResult: (String?) -> Unit) =
        mInstance?.startTakeoff { SDKUtils.createCompletionCallback(onResult) }

    fun confirmLanding(onResult: (String?) -> Unit) =
        // for some reason these methods are not calling onResult, possibly is a thread issue.
        mInstance?.confirmLanding { SDKUtils.createCompletionCallback(onResult) }

    fun armMotors(onResult: (String?) -> Unit) =
        mInstance?.turnOnMotors { SDKUtils.createCompletionCallback(onResult) }

    fun disarmMotors(onResult: (String?) -> Unit) =
        mInstance?.turnOffMotors { SDKUtils.createCompletionCallback(onResult) }

    fun sensorState(sensorState: SensorState?): AppSensorState = when (sensorState) {
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

    fun getIMUCount(): Int = mInstance?.imuCount ?: 0

    fun registerIMUStateCallback(stateCallback: (IMUState) -> Unit) =
        mInstance?.setIMUStateCallback(stateCallback)

    fun unregisterIMUStateCallback() = mInstance?.setIMUStateCallback(null)

    fun getCompassCount(): Int = mInstance?.compassCount ?: 0

    fun compassOk(): Boolean {
        if (getCompassCount() <= 0) {
            logger.i { "No Compass sensor found!" }
            return false
        }

        return mInstance?.compass?.let {
            !it.hasError() && it.calibrationState == CompassCalibrationState.NOT_CALIBRATING
        } ?: false
    }
}
