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
    private val logger by taggedLogger("SimulationManager")

    private var hasCallback: Boolean = false
    var simInstance: Simulator? = null
        private set
    private var satelliteCount: Int = -1

    @Synchronized
    fun init(flightController: FlightController) {
        simInstance = flightController.simulator
        logger.i { "Simulation init" }
    }

    fun isAvailable(): Boolean = simInstance != null

    fun isActive(): Boolean = simInstance?.isSimulatorActive ?: false

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
            velocityX = 0f,
            velocityY = 0f,
            velocityZ = 0f,
            flightTime = 0,
            takeOffAltitude = 0f,
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
        logger.d { "Starting Simulation" }
        this.satelliteCount = satelliteCount
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
        logger.d { "Stopping Simulation" }
        simInstance?.stop(SDKUtils.createCompletionCallback(onResult))
    }

}