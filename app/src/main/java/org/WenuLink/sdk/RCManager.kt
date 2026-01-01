package org.WenuLink.sdk

import org.WenuLink.adapters.BatteryData
import org.WenuLink.adapters.RCData
import dji.common.remotecontroller.BatteryState
import dji.sdk.remotecontroller.RemoteController
import io.getstream.log.taggedLogger
import kotlin.getValue
import kotlin.math.round

object RCManager {
    private val logger by taggedLogger("RCManager")
    private var lastData: RCData? = null
    private val lastBatteryData: BatteryData = BatteryData()
    private var rcInstance: RemoteController? = null

    @Synchronized
    fun init(remoteController: RemoteController)  {
        rcInstance = remoteController
        logger.i { "Remote Controller connected" }
    }

    @Synchronized
    fun isUpdated(): Boolean {
        return lastData != null && lastBatteryData.percentCharge > -1
    }

    @Synchronized
    fun isRCConnected(): Boolean {
        return rcInstance != null
    }

    fun startListeners() {
        startHardwareListener()
        startBatteryListener()
    }

    fun stopListeners() {
        stopBatteryListener()
        stopHardwareListener()
    }

    @Synchronized
    fun getBatteryData(): BatteryData {
        return lastBatteryData
    }

    @Synchronized
    fun getHardwareData(): RCData? {
        return lastData
    }

    @Synchronized
    private fun updateData(data: RCData?) {
        // TODO: safety: bajar MAVLink si detecta control de usuario
        lastData = data
    }

    @Synchronized
    private fun updateBatteryData(battery: BatteryData) {
        if (battery.voltageCells == null) {
            battery.voltageCells = IntArray(1)
        }
        battery.voltage = 7400
        battery.current = 6
        battery.voltageCells!![0] = round(7.4 * battery.percentCharge).toInt()
        lastBatteryData.updateFrom(battery)
    }

    private fun startHardwareListener() {
        logger.d { "Starting RC HardwareListener" }
        rcInstance?.setHardwareStateCallback { hardwareState -> // DJI: range [-660,660]
            updateData(
                RCData(
                    throttleSetting = (hardwareState.leftStick!!.verticalPosition + 660) / 1320,
                    // Mavlink: 1000 to 2000 with 1500 = 1.5ms as center...
                    leftStickVertical = (hardwareState.leftStick!!.verticalPosition * 0.8).toInt() + 1500,
                    leftStickHorizontal = (hardwareState.leftStick!!.horizontalPosition * 0.8).toInt() + 1500,
                    rightStickVertical = (hardwareState.rightStick!!.verticalPosition * 0.8).toInt() + 1500,
                    rightStickHorizontal = (hardwareState.rightStick!!.horizontalPosition * 0.8).toInt() + 1500,
                    buttonC1 = hardwareState.c1Button?.isClicked ?: false,
                    buttonC2 = hardwareState.c2Button?.isClicked ?: false,
                    buttonC3 = hardwareState.c3Button?.isClicked ?: false,
                    mode = hardwareState.flightModeSwitch
                )
            )
        }
    }

    private fun stopHardwareListener() {
        logger.d { "Stoping RC HardwareListener" }
        rcInstance?.setHardwareStateCallback { null }
        updateData(null)
    }

    private fun startBatteryListener() {
        logger.d { "Starting RC BatteryListener" }
        rcInstance?.setChargeRemainingCallback { batteryState: BatteryState ->
            updateBatteryData(
                BatteryData(batteryState.remainingChargeInPercent)
            )
        }
    }

    private fun stopBatteryListener() {
        logger.d { "Stopping RC BatteryListener" }
        rcInstance?.setChargeRemainingCallback { null }
        updateBatteryData(BatteryData())
    }
}