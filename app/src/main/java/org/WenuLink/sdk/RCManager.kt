package org.WenuLink.sdk

import android.util.Log
import org.WenuLink.adapters.BatteryData
import org.WenuLink.adapters.RCData
import dji.common.remotecontroller.BatteryState
import dji.sdk.remotecontroller.RemoteController

object RCManager {
    private val TAG: String = RCManager::class.java.simpleName
    private var lastData: RCData? = null
    private val lastBatteryData: BatteryData = BatteryData()
    private var rcInstance: RemoteController? = null

    @Synchronized
    fun init(remoteController: RemoteController)  {
        rcInstance = remoteController
        Log.i(TAG, "Remote Controller connected")
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
        lastBatteryData.updateFrom(battery)
    }

    private fun startHardwareListener() {
        Log.d(TAG, "Starting RC HardwareListener")
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
        Log.d(TAG, "Stoping RC HardwareListener")
        rcInstance?.setHardwareStateCallback { null }
        updateData(null)
    }

    private fun startBatteryListener() {
        Log.d(TAG, "Starting RC BatteryListener")
        rcInstance?.setChargeRemainingCallback { batteryState: BatteryState ->
            updateBatteryData(
                BatteryData(batteryState.remainingChargeInPercent)
            )
        }
    }

    private fun stopBatteryListener() {
        Log.d(TAG, "Stopping RC BatteryListener")
        rcInstance?.setChargeRemainingCallback { null }
        updateBatteryData(BatteryData())
    }
}