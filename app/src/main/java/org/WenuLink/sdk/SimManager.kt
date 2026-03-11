package org.WenuLink.sdk

import dji.common.flightcontroller.GPSSignalLevel
import dji.common.flightcontroller.simulator.InitializationData
import dji.common.flightcontroller.simulator.SimulatorState
import dji.common.model.LocationCoordinate2D
import dji.sdk.flightcontroller.FlightController
import dji.sdk.flightcontroller.Simulator
import io.getstream.log.taggedLogger
import org.WenuLink.adapters.TelemetryData

object SimManager {
    private val logger by taggedLogger(SimManager::class.java.simpleName)

    private var hasCallback: Boolean = false
    var simInstance: Simulator? = null
        private set
    private var satelliteCount: Int = -1
    private var velocityX: Float = 0f
    private var velocityY: Float = 0f
    private var velocityZ: Float = 0f
    private var flightTime: Int = 0
    private var takeOffAltitude: Float = 0f
    private var initStamp: Long = 0L
    private var updateStamp: Long = 0L
    private var takeOffStamp: Long = 0L
    private var landStamp: Long = 0L

    @Synchronized
    fun init(flightController: FlightController) {
        simInstance = flightController.simulator
        logger.i { "Simulation init" }
    }

    fun isAvailable(): Boolean = simInstance != null

    fun isActive(): Boolean = simInstance?.isSimulatorActive == true

    fun registerStateCallback(stateCallback: (SimulatorState) -> Unit) {
        if (hasCallback) unregisterStateCallback()
        simInstance?.setStateCallback(stateCallback)
        hasCallback = true
    }

    fun unregisterStateCallback() {
        simInstance?.setStateCallback(null)
        hasCallback = false
    }

    fun completeTelemetryData(telemetryT: TelemetryData, state: SimulatorState) {
        val stamp = System.currentTimeMillis() / 1000  // to seconds
        val dT = stamp - updateStamp
        velocityX = (state.positionX - telemetryT.positionX) / dT
        velocityY = (state.positionY - telemetryT.positionY) / dT
        velocityZ = (state.positionZ - telemetryT.positionZ) / dT

        if (state.isFlying && !telemetryT.isFlying) {
            takeOffStamp = stamp
            landStamp = stamp
        }

        if (state.isFlying && telemetryT.isFlying) landStamp = stamp

        flightTime = (landStamp - takeOffStamp).toInt()
        updateStamp = stamp
    }

    @Synchronized
    fun state2telemetry(state: SimulatorState): TelemetryData {
        return TelemetryData(
            roll = state.roll.toDouble(),
            pitch = state.pitch.toDouble(),
            yaw = state.yaw.toDouble(),
            latitude = state.location.latitude,
            longitude = state.location.longitude,
            altitude = state.positionZ,
            positionX = state.positionX,
            positionY = state.positionY,
            positionZ = state.positionZ,
            velocityX = velocityX,
            velocityY = velocityY,
            velocityZ = velocityZ,
            flightTime = flightTime,
            takeOffAltitude = takeOffAltitude,
            isFlying = state.isFlying,
            motorsOn = state.areMotorsOn(),
            satelliteCount = satelliteCount,
            gpsLevel = SDKUtils.getGPSSignalLevelArray(GPSSignalLevel.LEVEL_7)
        )
    }

    fun run(
        lat: Double = -8.066478642777481,
        long: Double = -34.98744367551871,
        updateFrequency: Int = 10,
        satelliteCount: Int = 8,
        onResult: (String?) -> Unit
    ) {
        if (isActive()) {
            onResult(null)
            return
        }
        logger.d { "Simulation start." }
        this.satelliteCount = satelliteCount
        initStamp = System.currentTimeMillis() / 1000  // to seconds
        updateStamp = initStamp
        velocityX = 0f
        velocityY = 0f
        velocityZ = 0f
        flightTime = 0
        takeOffAltitude = 0f

        simInstance?.start(
            InitializationData.createInstance(
                LocationCoordinate2D(lat, long),
                updateFrequency,
                satelliteCount
            ),
            SDKUtils.createCompletionCallback(onResult)
        )
    }

    fun stop(onResult: (String?) -> Unit) {
        if (!isActive()) {
            onResult(null)
            return
        }
        logger.d { "Simulation stop." }
        simInstance?.stop(SDKUtils.createCompletionCallback(onResult))
    }

}