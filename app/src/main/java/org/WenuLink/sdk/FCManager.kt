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

    fun isFlying(): Boolean {
        return fcInstance?.state?.isFlying ?: false
    }

    suspend fun waitForAltitude(altitude: Float = 1.2F, margin: Float = 0.1F) {
        fun hasDiffAltitude() = abs(fcInstance?.state?.aircraftLocation?.altitude?.minus(altitude) ?: margin) < margin
        while (hasDiffAltitude()) {
            logger.d { "hasDiffAltitude(${fcInstance?.state?.aircraftLocation?.altitude})" }
            delay(100L)
        }
    }

    suspend fun waitForFlying(takingOff: Boolean): Boolean {
        logger.d { "Waiting for ${if (takingOff) "taking off" else "touching ground"}" }
        fun isFlyingConditioned() = if (takingOff) isFlying() else !isFlying()
        AsyncUtils.waitReady( 100L, isReady = ::isFlyingConditioned)
        logger.d { "Aircraft is ${if (isFlyingConditioned() && takingOff) "flying" else "on the ground"}" }
        return isFlyingConditioned()
    }

    suspend fun simpleTakeoff(): Boolean {
//        fcInstance?.startTakeoff { SDKUtils.createCompletionCallback(onResult) }
        fcInstance?.startTakeoff { }
        return waitForFlying(true)
    }

    suspend fun simpleLanding(): Boolean {
        logger.d { "Landing" }
//        fcInstance?.startLanding { SDKUtils.createCompletionCallback(onResult) }
        fcInstance?.startLanding { }

        fun confirmationNeeded() = fcInstance?.state?.isLandingConfirmationNeeded == true

        logger.d { "\t- Descending to 0.3m" }
        AsyncUtils.waitReady(100L, ::confirmationNeeded)

        // TODO: Assess if must ask to user to accept this confirmation
        logger.d { "\t- Waiting for confirmation" }
        fcInstance?.confirmLanding {
            // for somehow these kind of actions does not return anything, possibly is a thread issue.
            SDKUtils.createCompletionCallback { error ->
                logger.d { "\t\tconfirmLanding error: $error" }
            }
        }

        return waitForFlying(false)
    }

    fun areMotorsArmed(): Boolean {
        return fcInstance?.state?.areMotorsOn() ?: false
    }

    suspend fun waitForArmed(arming: Boolean): Boolean {
        fun areMotorsUpdated() = if (arming) areMotorsArmed() else !areMotorsArmed()
        val motorsUpdated = AsyncUtils.waitTimeout(isReady = ::areMotorsUpdated)
        logger.d { "Motors ${if (motorsUpdated && arming) "armed" else "disarmed"}: $motorsUpdated" }
        return motorsUpdated
    }

    suspend fun armMotors(): Boolean {
        logger.d { "Arming motors" }
//        fcInstance?.turnOnMotors { SDKUtils.createCompletionCallback(onResult) }
        fcInstance?.turnOnMotors { } // apparently ignores the callback and must wait for change to happen
        return waitForArmed(true)
    }

    suspend fun disarmMotors(): Boolean {
        logger.d { "Disarming motors" }
//        fcInstance?.turnOffMotors { SDKUtils.createCompletionCallback(onResult) }
        fcInstance?.turnOffMotors { } // apparently ignores the callback and must wait for change to happen
        return waitForArmed(false)
    }

}
