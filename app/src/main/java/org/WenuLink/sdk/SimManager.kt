package org.WenuLink.sdk

import dji.common.flightcontroller.GPSSignalLevel
import dji.common.flightcontroller.simulator.InitializationData
import dji.common.flightcontroller.simulator.SimulatorState
import dji.common.model.LocationCoordinate2D
import dji.sdk.flightcontroller.FlightController
import dji.sdk.flightcontroller.Simulator
import io.getstream.log.taggedLogger
import org.WenuLink.adapters.aircraft.TelemetryData

object SimManager {
    private val logger by taggedLogger(SimManager::class.java.simpleName)

    private var hasCallback: Boolean = false
    var simInstance: Simulator? = null
        private set
    private var satelliteCount: Int = -1
    private var takeOffAltitude: Float = 0F
    private var initStamp: Long = 0L
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

    @Synchronized
    fun state2telemetry(state: SimulatorState, previousData: TelemetryData? = null): TelemetryData {
        val data = TelemetryData(
            roll = state.roll.toDouble(),
            pitch = state.pitch.toDouble(),
            yaw = state.yaw.toDouble(),
            latitude = state.location.latitude,
            longitude = state.location.longitude,
            positionX = state.positionX,
            positionY = state.positionY,
            positionZ = state.positionZ,
            velocityX = 0F,
            velocityY = 0F,
            velocityZ = 0F,
            flightTime = 0,
            takeOffAltitude = takeOffAltitude,
            relativeAltitude = state.positionZ,
            isFlying = state.isFlying,
            motorsOn = state.areMotorsOn(),
            satelliteCount = satelliteCount,
            gpsLevel = SDKUtils.getGPSSignalLevelArray(GPSSignalLevel.LEVEL_7)
        )
        // complete data from previous one
        if (previousData == null) return data

        // perform updates comparing previous and current state
        if (data.isFlying) {
            if (!previousData.isFlying) takeOffStamp = data.timestamp
            landStamp = data.timestamp
        }

        // compute velocities
        val dT = (data.timestamp - previousData.timestamp).toFloat() / 1000F // to seconds
        return data.copy(
            flightTime = (landStamp - takeOffStamp).toInt(),
            velocityX = (data.positionX - previousData.positionX) / dT,
            velocityY = (data.positionY - previousData.positionY) / dT,
            velocityZ = (data.positionZ - previousData.positionZ) / dT
        )
    }

    fun run(
        lat: Double = -8.066478642777481,
        long: Double = -34.98744367551871,
        alt: Float = 24.0F,
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
        initStamp = System.currentTimeMillis() / 1000 // to seconds
        takeOffAltitude = alt

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
