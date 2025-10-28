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
    val velocityX: Float,
    val velocityY: Float,
    val velocityZ: Float,
    val flightTime: Int,
    val flightMode: ArduCopterFlightMode,
    val flightGuided: Boolean,
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

enum class ArduCopterFlightMode(val mode: Long) {
    STABILIZE(0),
    ACRO(1),
    ALT_HOLD(2),
    AUTO(3),
    GUIDED(4),
    LOITER(5),
    RTL(6),
    CIRCLE(7),
    LAND(9),
    DRIFT(11),
    SPORT(13),
    FLIP(14),
    AUTOTUNE(15),
    POSHOLD(16),
    BRAKE(17),
    THROW(18),
    AVOID_ADSB(19),
    GUIDED_NOGPS(20),
    SMART_RTL(21);
}

/**
 * Generic command wrapper for sending commands to the drone.
 * Could be extended to contain MAVLink messages or DJI-specific commands.
 */
data class DroneCommand(
    val type: CommandType,
    val payload: Any
)

enum class CommandType {
    TAKE_OFF,
    LAND,
    MOVE,
    CUSTOM
}

