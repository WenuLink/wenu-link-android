package org.WenuLink.adapters


import dji.common.remotecontroller.HardwareState.FlightModeSwitch

/**
 * Data class to hold telemetry info.
 */
data class TelemetryData(
    val roll: Double,
    val pitch: Double,
    val yaw: Double,
    val latitude: Double,
    val longitude: Double,
    val altitude: Float,
    val positionX: Float,
    val positionY: Float,
    val positionZ: Float,
    val velocityX: Float,
    val velocityY: Float,
    val velocityZ: Float,
    val flightTime: Int,
    val takeOffAltitude: Float,
    val isFlying: Boolean,
    val motorsOn: Boolean,
    val satelliteCount: Int,
    // DJI reports signal quality on a scale of 1-11
    // Mavlink has separate codes for fix type.
    val gpsLevel: BooleanArray,
    val gpsFixType: Int = 0
)

data class RCData(
    val throttleSetting: Int,
    val leftStickVertical: Int,
    val leftStickHorizontal: Int,
    val rightStickVertical: Int,
    val rightStickHorizontal: Int,
    val buttonC1: Boolean,
    val buttonC2: Boolean,
    val buttonC3: Boolean,
    val mode: FlightModeSwitch?
)

data class BatteryData(
    var percentCharge: Int = -1,
    var voltage: Int = -1,
    var current: Int = -1,
    var fullChargeCapacity: Int = -1,
    var chargeRemaining: Int = -1,
    var temperature: Float = -1.0F,
    var voltageCells: IntArray? = null
) {

    fun updateFrom(other: BatteryData) {
        if (other.percentCharge != -1) percentCharge = other.percentCharge
        if (other.voltage != -1) voltage = other.voltage
        if (other.current != -1) current = other.current
        if (other.fullChargeCapacity != -1) fullChargeCapacity = other.fullChargeCapacity
        if (other.chargeRemaining != -1) chargeRemaining = other.chargeRemaining
        if (other.temperature != -1.0F) temperature = other.temperature
        if (other.voltageCells != null) voltageCells = other.voltageCells?.clone() // Clone to avoid reference issues
    }

    override fun toString(): String {
        return "BatteryData(percentCharge=$percentCharge%, voltage=$voltage V, current=$current A, " +
                "fullChargeCapacity=$fullChargeCapacity A, fullChargeCapacity=$chargeRemaining A, temperature=$temperature °C, voltageCells=${voltageCells?.joinToString()})"
    }
}

data class Coordinates3D(
    val lat: Double,
    val long: Double,
    val alt: Float
)

data class MessageRate(
    val messageID: Int,
    var microSecondsInterval: Long,
    var lastUpdateStamp: Long = 0
)
