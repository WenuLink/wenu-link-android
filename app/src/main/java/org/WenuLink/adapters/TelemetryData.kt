package org.WenuLink.adapters

import com.MAVLink.enums.CAMERA_MODE
import dji.common.remotecontroller.HardwareState.FlightModeSwitch
import kotlin.Int
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
    val gpsLevel: BooleanArray,
    val gpsFixType: Int = 0
)

data class MAVLinkTelemetryData(
    val timestamp: Long,
    val roll: Float,
    val pitch: Float,
    val yaw: Float,
    val latitude: Int,     // scaled 1E7
    val longitude: Int,    // scaled 1E7
    val relativeAltitude: Int,     // millimeters
    val takeOffAltitude: Int,     // millimeters
    val altitude: Int = relativeAltitude + takeOffAltitude,     // millimeters
    val velocityX: Short,
    val velocityY: Short,
    val velocityZ: Short,
    val satelliteCount: Int,
    val gpsFixType: Int
)

object TelemetryMapper {

    fun toMavlink(source: TelemetryData): MAVLinkTelemetryData {
        return MAVLinkTelemetryData(
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

enum class CameraCaptureType {
    UNSET,
    IMAGE,
    VIDEO
}

enum class CameraCaptureStatus(val value: Int) {
    IDLE(0),
    IN_PROGRESS(1),
    INTERVAL_IDLE(2),
    INTERVAL_PROGRESS(3)
}

data class CameraState(
    val mavlinkMode: Int = CAMERA_MODE.CAMERA_MODE_IMAGE,
    val captureType: CameraCaptureType = CameraCaptureType.IMAGE,
    val captureStatus: CameraCaptureStatus = CameraCaptureStatus.IDLE,
    /**
     * captureTime depends on CaptureType:
     * - CaptureType.IMAGE: capture interval in seconds
     * - CaptureType.VIDEO: elapsed recording time in milliseconds
     */
    val captureTime: Long = 0
)

data class CameraMetadata(
    val id: Int = 1,
    val streamID: String,
    val name: String,
    val fwVersion: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val state: CameraState = CameraState()
)

data class ImageMetadata(
    val index: Int,
    val captureOk: Boolean,
    val cameraID: Int,
    val telemetry: TelemetryData,
)

data class Quaternion(
    val w: Double,
    val x: Double,
    val y: Double,
    val z: Double
) {
    fun normalized(): Quaternion {
        val norm = kotlin.math.sqrt(w*w + x*x + y*y + z*z)
        return Quaternion(w / norm, x / norm, y / norm, z / norm)
    }

    fun toFloatArray(): FloatArray {
        return floatArrayOf(
            w.toFloat(),
            x.toFloat(),
            y.toFloat(),
            z.toFloat()
        )
    }
}
