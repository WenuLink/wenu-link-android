package org.WenuLink.sdk

import dji.common.remotecontroller.BatteryState
import dji.sdk.remotecontroller.RemoteController
import io.getstream.log.taggedLogger
import kotlin.math.round
import org.WenuLink.adapters.BatteryData
import org.WenuLink.adapters.RCData

object RCManager {
    private val logger by taggedLogger(RCManager::class.java.simpleName)
    private var lastData: RCData? = null
    private val lastBatteryData: BatteryData = BatteryData()
    private var rcInstance: RemoteController? = null

    @Synchronized
    fun init(remoteController: RemoteController) {
        rcInstance = remoteController
        logger.i { "Remote Controller connected" }
    }

    @Synchronized
    fun isUpdated(): Boolean = lastData != null && lastBatteryData.percentCharge > -1

    @Synchronized
    fun isRCConnected(): Boolean = rcInstance != null

    fun startListeners() {
        startHardwareListener()
        startBatteryListener()
    }

    fun stopListeners() {
        stopBatteryListener()
        stopHardwareListener()
    }

    @Synchronized
    fun getBatteryData(): BatteryData = lastBatteryData

    @Synchronized
    fun getHardwareData(): RCData? = lastData

    @Synchronized
    private fun updateData(data: RCData?) {
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
        rcInstance?.setHardwareStateCallback { hardwareState ->
            updateData(
                RCData(
                    throttleSetting = hardwareState.leftStick!!.verticalPosition,
                    leftStickVertical = hardwareState.leftStick!!.verticalPosition,
                    leftStickHorizontal = hardwareState.leftStick!!.horizontalPosition,
                    rightStickVertical = hardwareState.rightStick!!.verticalPosition,
                    rightStickHorizontal = hardwareState.rightStick!!.horizontalPosition,
                    buttonC1 = hardwareState.c1Button?.isClicked == true,
                    buttonC2 = hardwareState.c2Button?.isClicked == true,
                    buttonC3 = hardwareState.c3Button?.isClicked == true,
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
