package org.WenuLink.sdk

import org.WenuLink.adapters.BatteryData
import dji.common.battery.BatteryState
import dji.common.error.DJIError
import dji.common.util.CommonCallbacks.CompletionCallbackWith
import dji.sdk.airlink.AirLink
import dji.sdk.battery.Battery
import dji.sdk.products.Aircraft
import io.getstream.log.taggedLogger
import kotlin.getValue


object AircraftManager {
    private val logger by taggedLogger("AircraftManager")
    private val lastBatteryData: BatteryData = BatteryData()
    private val lastAirLinkQuality: IntArray = intArrayOf(-1, -1)
    private var aircraftInstance: Aircraft? = null
    private var batteryInstance: Battery? = null
    private var airLinkInstance: AirLink? = null
    private var useAirLink: Boolean = false

    @Synchronized
    fun init(aircraft: Aircraft)  {
        aircraftInstance = aircraft
        batteryInstance = aircraft.battery
        CameraManager.updateStreamID(getModel())
        logger.i { "Aircraft connected: ${getModelName()}" }
    }

    @Synchronized
    fun initAirLink(airLink: AirLink)  {
        this.airLinkInstance = airLink
        useAirLink = true
        logger.i { "AirLink connected" }
    }

    @Synchronized
    fun isUpdated(): Boolean {
        var isUpdated = lastBatteryData.percentCharge > -1
        if (useAirLink) {
            isUpdated = isUpdated && lastAirLinkQuality[0] > -1 &&
                    lastAirLinkQuality[1] > -1
        }
        return isUpdated
    }

    @Synchronized
    fun isAircraftConnected(): Boolean {
        return aircraftInstance != null
    }

    fun startListeners() {
        startBatteryListeners()
        startAirLinkListeners()
    }

    fun stopListeners() {
        stopAirLinkListeners()
        stopBatteryListeners()
    }

    @Synchronized
    fun getBatteryData(): BatteryData {
        return lastBatteryData
    }

    fun getModelName(): String {
        return aircraftInstance?.model?.displayName ?: "No aircraft"
    }

    fun getModel(): String {
        return aircraftInstance?.model?.toString() ?: "NONE"
    }

    @Synchronized
    private fun updateBattery(battery: BatteryData) {
        lastBatteryData.updateFrom(battery)
    }

    @Synchronized
    private fun updateBatteryCellVoltages(cellVoltage: IntArray) {
        lastBatteryData.voltageCells = cellVoltage.clone()
    }

    @Synchronized
    private fun updateAirlink(downLink: Int?, upLink: Int?) {
        if (downLink != null) lastAirLinkQuality[0] = downLink
        if (upLink != null) lastAirLinkQuality[1] = upLink
    }

    private fun startBatteryListeners() {
        logger.d { "Starting Battery updates" }
        batteryInstance?.setStateCallback { batteryState: BatteryState ->
            updateBattery(
                BatteryData(
                    batteryState.chargeRemainingInPercent,
                    batteryState.voltage,
                    batteryState.current,
                    batteryState.fullChargeCapacity,
                    batteryState.chargeRemaining,
                    batteryState.temperature,
                )
            )
        }

        batteryInstance?.getCellVoltages(object: CompletionCallbackWith<Array<Int>> {
            override fun onSuccess(p0: Array<Int>) {
                updateBatteryCellVoltages(p0.toIntArray())
                logger.d { "getCellVoltages $p0" }
            }

            override fun onFailure(p0: DJIError?) {
                // Silently pass
            }
        })
    }

    private fun stopBatteryListeners() {
        logger.d { "Stopping Battery updates" }
        batteryInstance?.setStateCallback { null }
        updateBattery(BatteryData())
    }

    private fun startAirLinkListeners() {
        if (!useAirLink) {
            updateAirlink(-1, -1)
            logger.w { "Unable to start AirLink updates, no AirLink present" }
            return
        }
        logger.d { "Starting AirLink updates" }
        airLinkInstance?.setDownlinkSignalQualityCallback { i: Int -> updateAirlink(i, null) }
        airLinkInstance?.setUplinkSignalQualityCallback { i: Int -> updateAirlink(null, i) }
    }

    private fun stopAirLinkListeners() {
        logger.d { "Stopping AirLink updates" }
        airLinkInstance?.setDownlinkSignalQualityCallback{ i -> }
        airLinkInstance?.setUplinkSignalQualityCallback{ i -> }
        updateAirlink(-1, -1)
    }
}