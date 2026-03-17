package org.WenuLink.adapters

import dji.common.remotecontroller.HardwareState.FlightModeSwitch
import kotlin.math.roundToInt

/**
 * Data class to hold telemetry info.
 */
data class TelemetryData(
    val timestamp: Long = System.currentTimeMillis(),
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
) {
    private fun stickValue2percent(value: Int): Int {
        // transform from DJI range [-660, 660] => [0, 100]
        return (((value + 660).toFloat() / 1320f) * 100).roundToInt()
    }

    private fun stickValue2rcValue(value: Int): Int {
        // transform from DJI range [-660, 660] => [1000, 2000]
        return ((value.toFloat() / 660) * 500).roundToInt() + 1500
    }

    fun toMAVLink(): RCData {
        val currRC = this.copy(
            throttleSetting = stickValue2percent(this.throttleSetting),
            leftStickVertical = stickValue2rcValue(this.leftStickVertical),
            leftStickHorizontal = stickValue2rcValue(this.leftStickHorizontal),
            rightStickVertical = stickValue2rcValue(this.rightStickVertical),
            rightStickHorizontal = stickValue2rcValue(this.rightStickHorizontal)
        )
        return currRC
    }

    fun hasCenteredJoystick(): Boolean = this.leftStickVertical == 0 &&
        this.leftStickHorizontal == 0 &&
        this.rightStickVertical == 0 &&
        this.rightStickHorizontal == 0
}

data class BatteryData(
    var percentCharge: Int = -1,
    var voltage: Int = -1,
    var current: Int = -1,
    var fullChargeCapacity: Int = -1,
    var chargeRemaining: Int = -1,
    var temperature: Float = -1.0f,
    var voltageCells: IntArray? = null
) {

    fun updateFrom(other: BatteryData) {
        if (other.percentCharge != -1) percentCharge = other.percentCharge
        if (other.voltage != -1) voltage = other.voltage
        if (other.current != -1) current = other.current
        if (other.fullChargeCapacity != -1) fullChargeCapacity = other.fullChargeCapacity
        if (other.chargeRemaining != -1) chargeRemaining = other.chargeRemaining
        if (other.temperature != -1.0f) temperature = other.temperature
        other.voltageCells?.let {
            voltageCells = other.voltageCells?.clone() // Clone to avoid reference issues
        }
    }

    override fun toString(): String = "BatteryData(" +
        "percentCharge=$percentCharge%, " +
        "voltage=$voltage V, " +
        "current=$current A, " +
        "fullChargeCapacity=$fullChargeCapacity A, " +
        "fullChargeCapacity=$chargeRemaining A, " +
        "temperature=$temperature °C, " +
        "voltageCells=${voltageCells?.joinToString()})"
}

data class Coordinates3D(val lat: Double, val long: Double, val alt: Float)

data class MessageRate(
    val messageID: Int,
    var microSecondsInterval: Long,
    var lastUpdateStamp: Long = 0
)

enum class SensorState {
    BOOT, // Boot states
    CALIBRATION_NEEDED, // Calibration needed states
    OK // Ok states
}

data class IMUState(
    val gyroscope: MutableList<SensorState> = mutableListOf(),
    val accelerometer: MutableList<SensorState> = mutableListOf()
)
