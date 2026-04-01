package org.WenuLink.adapters.aircraft

import dji.common.remotecontroller.HardwareState.FlightModeSwitch
import kotlin.Int
import kotlin.math.roundToInt
import kotlin.math.sqrt
import org.WenuLink.adapters.MessageUtils

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
    val positionX: Float,
    val positionY: Float,
    val positionZ: Float,
    val velocityX: Float,
    val velocityY: Float,
    val velocityZ: Float,
    val flightTime: Int,
    val takeOffAltitude: Float,
    val relativeAltitude: Float,
    val altitude: Float = takeOffAltitude + relativeAltitude,
    val isFlying: Boolean,
    val motorsOn: Boolean,
    val satelliteCount: Int,
    // DJI reports signal quality on a scale of 1-11
    // Mavlink has separate codes for fix type.
    val gpsLevel: List<Boolean>,
    val gpsFixType: Int = 0
)

data class MAVLinkTelemetryData(
    val timestamp: Long,
    val roll: Float,
    val pitch: Float,
    val yaw: Float,
    val latitude: Int, // scaled 1E7
    val longitude: Int, // scaled 1E7
    val relativeAltitude: Int, // millimeters
    val takeOffAltitude: Int, // millimeters
    val altitude: Int = relativeAltitude + takeOffAltitude, // millimeters
    val velocityX: Short,
    val velocityY: Short,
    val velocityZ: Short,
    val satelliteCount: Int,
    val gpsFixType: Int
)

object TelemetryMapper {
    fun toMavlink(source: TelemetryData): MAVLinkTelemetryData = MAVLinkTelemetryData(
        timestamp = source.timestamp,
        roll = source.roll.toFloat(),
        pitch = source.pitch.toFloat(),
        yaw = source.yaw.toFloat(),
        latitude = MessageUtils.coordinateDJI2MAVLink(source.latitude),
        longitude = MessageUtils.coordinateDJI2MAVLink(source.longitude),
        relativeAltitude = MessageUtils.altitudeDJI2MAVLink(source.relativeAltitude),
        takeOffAltitude = MessageUtils.altitudeDJI2MAVLink(source.takeOffAltitude),
        velocityX = (source.velocityX * 100).roundToInt().toShort(),
        velocityY = (source.velocityY * 100).roundToInt().toShort(),
        velocityZ = (source.velocityZ * 100).roundToInt().toShort(),
        satelliteCount = source.satelliteCount,
        gpsFixType = source.gpsFixType
    )
}

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
    private fun stickValue2Percent(value: Int): Int =
        // transform from DJI range [-660, 660] => [0, 100]
        (((value + 660).toFloat() / 1320f) * 100).roundToInt()

    private fun stickValue2RcValue(value: Int): Int =
        // transform from DJI range [-660, 660] => [1000, 2000]
        ((value.toFloat() / 660) * 500).roundToInt() + 1500

    fun toMAVLink(): RCData = this.copy(
        throttleSetting = stickValue2Percent(this.throttleSetting),
        leftStickVertical = stickValue2RcValue(this.leftStickVertical),
        leftStickHorizontal = stickValue2RcValue(this.leftStickHorizontal),
        rightStickVertical = stickValue2RcValue(this.rightStickVertical),
        rightStickHorizontal = stickValue2RcValue(this.rightStickHorizontal)
    )

    fun hasCenteredJoystick(): Boolean = listOf(
        this.leftStickVertical,
        this.leftStickHorizontal,
        this.rightStickVertical,
        this.rightStickHorizontal
    ).all { it == 0 }
}

data class BatteryData(
    val percentCharge: Int? = null,
    val voltage: Int? = null,
    val current: Int? = null,
    val fullChargeCapacity: Int? = null,
    val chargeRemaining: Int? = null,
    val temperature: Float? = null,
    val voltageCells: List<Int>? = null
) {
    fun merge(
        percentCharge: Int? = null,
        voltage: Int? = null,
        current: Int? = null,
        fullChargeCapacity: Int? = null,
        chargeRemaining: Int? = null,
        temperature: Float? = null,
        voltageCells: List<Int>? = null
    ): BatteryData = copy(
        percentCharge = percentCharge ?: this.percentCharge,
        voltage = voltage ?: this.voltage,
        current = current ?: this.current,
        fullChargeCapacity = fullChargeCapacity ?: this.fullChargeCapacity,
        chargeRemaining = chargeRemaining ?: this.chargeRemaining,
        temperature = temperature ?: this.temperature,
        voltageCells = voltageCells ?: this.voltageCells
    )

    override fun toString(): String = "BatteryData(" +
        "percentCharge=$percentCharge%, " +
        "voltage=$voltage mV, " +
        "current=$current mA, " +
        "fullChargeCapacity=$fullChargeCapacity mAh, " +
        "chargeRemaining=$chargeRemaining mAh, " +
        "temperature=$temperature °C, " +
        "voltageCells=${voltageCells?.joinToString()})"
}

data class MAVLinkBatteryData(
    val currentConsumed: Int, // mAh, -1 = unknown
    val temperature: Short, // cdegC, INT16_MAX = unknown
    val voltages: List<Int>, // mV
    val currentBattery: Short, // cA, -1 = unknown
    val batteryRemaining: Byte, // %, -1 = unknown
    val voltagesBattery: Int, // mV, -1 = unknown
    val currentBatteryRaw: Short // 10mA, -1 = unknown
)

object BatteryMapper {
    private const val UNKNOWN_INT = -1
    private const val UNKNOWN_SHORT = Short.MAX_VALUE // INT16_MAX per MAVLink spec

    fun toMavlink(source: BatteryData): MAVLinkBatteryData {
        val fullCharge = source.fullChargeCapacity
        val remaining = source.chargeRemaining
        return MAVLinkBatteryData(
            currentConsumed = if (fullCharge != null && remaining != null) {
                fullCharge - remaining
            } else {
                UNKNOWN_INT
            },
            temperature = source.temperature
                ?.let { (it * 100.0).toInt().toShort() }
                ?: UNKNOWN_SHORT,
            voltages = source.voltageCells ?: emptyList(),
            currentBattery = source.current
                ?.let { (it * 10).toShort() }
                ?: UNKNOWN_INT.toShort(),
            batteryRemaining = if (fullCharge != null && remaining != null) {
                (remaining.toFloat() / fullCharge * 100f).toInt().toByte()
            } else {
                UNKNOWN_INT.toByte()
            },
            voltagesBattery = source.voltage ?: UNKNOWN_INT,
            currentBatteryRaw = source.current?.toShort() ?: UNKNOWN_INT.toShort()
        )
    }
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

data class Quaternion(val w: Double, val x: Double, val y: Double, val z: Double) {
    fun normalized(): Quaternion {
        val norm = sqrt(w * w + x * x + y * y + z * z)
        return Quaternion(w / norm, x / norm, y / norm, z / norm)
    }

    fun toFloatArray(): FloatArray = floatArrayOf(
        w.toFloat(),
        x.toFloat(),
        y.toFloat(),
        z.toFloat()
    )
}
