package org.WenuLink.sdk

import dji.common.battery.BatteryState
import dji.common.error.DJIError
import dji.common.util.CommonCallbacks.CompletionCallbackWith
import dji.sdk.airlink.AirLink
import dji.sdk.battery.Battery
import dji.sdk.products.Aircraft
import io.getstream.log.taggedLogger
import org.WenuLink.adapters.aircraft.BatteryData
import org.WenuLink.adapters.aircraft.SignalQuality

object AircraftManager {
    private val logger by taggedLogger(AircraftManager::class.java.simpleName)
    private var lastBatteryData = BatteryData()
    private var downlinkQuality: Int? = null
    private var uplinkQuality: Int? = null
    private var aircraftInstance: Aircraft? = null
    private var batteryInstance: Battery? = null
    private var airLinkInstance: AirLink? = null
    private var useAirLink = false

    @Synchronized
    fun init(aircraft: Aircraft) {
        aircraftInstance = aircraft
        batteryInstance = aircraft.battery
        CameraManager.updateStreamID(getModel())
        logger.i { "Aircraft present: ${getModelName()}" }
    }

    @Synchronized
    fun isConnected(): Boolean = aircraftInstance != null

    @Synchronized
    fun initAirLink(airLink: AirLink) {
        this.airLinkInstance = airLink
        useAirLink = true
        logger.i { "AirLink present" }
    }

    @Synchronized
    fun isUpdated(): Boolean = lastBatteryData.percentCharge != null &&
        (!useAirLink || (downlinkQuality != null && uplinkQuality != null))

    @Synchronized
    fun isAircraftConnected(): Boolean = aircraftInstance != null

    fun startListeners() {
        startBatteryListeners()
        startAirLinkListeners()
    }

    fun stopListeners() {
        stopAirLinkListeners()
        stopBatteryListeners()
    }

    @Synchronized
    fun getBatteryData(): BatteryData = lastBatteryData

    fun getModelName(): String = aircraftInstance?.model?.displayName ?: "No aircraft"

    fun getModel(): String = aircraftInstance?.model?.toString() ?: "NONE"

    @Synchronized
    private fun updateBattery(state: BatteryState) {
        lastBatteryData = lastBatteryData.merge(
            state.chargeRemainingInPercent,
            state.voltage,
            state.current,
            state.fullChargeCapacity,
            state.chargeRemaining,
            state.temperature
        )
    }

    @Synchronized
    private fun updateBatteryCellVoltages(cellVoltage: List<Int>) {
        lastBatteryData = lastBatteryData.merge(voltageCells = cellVoltage)
    }

    @Synchronized
    fun getAirlinkData(): SignalQuality = SignalQuality(downlinkQuality, uplinkQuality)

    @Synchronized
    private fun updateAirlink(downLink: Int?, upLink: Int?) {
        downLink?.let { downlinkQuality = it }
        upLink?.let { uplinkQuality = it }
    }

    private fun startBatteryListeners() {
        logger.d { "Starting Battery updates" }
        batteryInstance?.setStateCallback { state -> updateBattery(state) }

        batteryInstance?.getCellVoltages(object : CompletionCallbackWith<Array<Int>> {
            override fun onSuccess(p0: Array<Int>) {
                updateBatteryCellVoltages(p0.toList())
                logger.d { "getCellVoltages $p0" }
            }

            override fun onFailure(p0: DJIError?) {
                // Silently pass
            }
        })
    }

    private fun stopBatteryListeners() {
        logger.d { "Stopping Battery updates" }
        batteryInstance?.setStateCallback { }
        lastBatteryData = BatteryData()
    }

    private fun startAirLinkListeners() {
        if (!useAirLink) {
            updateAirlink(null, null)
            logger.w { "Unable to start AirLink updates, no AirLink present" }
            return
        }
        logger.d { "Starting AirLink updates" }
        airLinkInstance?.setDownlinkSignalQualityCallback { updateAirlink(it, null) }
        airLinkInstance?.setUplinkSignalQualityCallback { updateAirlink(null, it) }
    }

    private fun stopAirLinkListeners() {
        logger.d { "Stopping AirLink updates" }
        airLinkInstance?.setDownlinkSignalQualityCallback { }
        airLinkInstance?.setUplinkSignalQualityCallback { }
        updateAirlink(null, null)
    }
}
