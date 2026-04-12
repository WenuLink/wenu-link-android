package org.WenuLink.sdk

import dji.sdk.remotecontroller.RemoteController
import io.getstream.log.taggedLogger
import org.WenuLink.adapters.aircraft.BatteryData
import org.WenuLink.adapters.aircraft.RCData

object RCManager {
    private val logger by taggedLogger(RCManager::class.java.simpleName)
    private var lastData: RCData? = null
    private var lastBatteryData = BatteryData()
    private var rcInstance: RemoteController? = null

    @Synchronized
    fun init(remoteController: RemoteController) {
        rcInstance = remoteController
        logger.i { "Remote Controller present" }
    }

    @Synchronized
    fun isUpdated(): Boolean = lastData != null && lastBatteryData.percentCharge != null

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
    private fun updateBatteryData(percentCharge: Int) {
        // simulated 2S LiPo, equal cell distribution
        val cellVoltage = 3700 * percentCharge / 100
        lastBatteryData = lastBatteryData.merge(
            percentCharge = percentCharge,
            voltage = 7400,
            current = 6,
            voltageCells = listOf(cellVoltage, cellVoltage)
        )
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
        rcInstance?.setHardwareStateCallback { }
        updateData(null)
    }

    private fun startBatteryListener() {
        logger.d { "Starting RC BatteryListener" }
        rcInstance?.setChargeRemainingCallback { state ->
            updateBatteryData(state.remainingChargeInPercent)
        }
    }

    private fun stopBatteryListener() {
        logger.d { "Stopping RC BatteryListener" }
        rcInstance?.setChargeRemainingCallback { }
        lastBatteryData = BatteryData()
    }
}
