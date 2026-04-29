package org.WenuLink.views

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.WenuLink.WenuLinkPreferences

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val _mavlinkIp = MutableStateFlow(WenuLinkPreferences.getMavlinkIp(application))
    val mavlinkIp: StateFlow<String> = _mavlinkIp.asStateFlow()
    private val _mavlinkPort = MutableStateFlow(WenuLinkPreferences.getMavlinkPort(application))
    val mavlinkPort: StateFlow<Int> = _mavlinkPort.asStateFlow()

    private val _webrtcIp = MutableStateFlow(WenuLinkPreferences.getWebRtcIp(application))
    val webrtcIp: StateFlow<String> = _webrtcIp.asStateFlow()
    private val _webrtcPort = MutableStateFlow(WenuLinkPreferences.getWebRtcPort(application))
    val webrtcPort: StateFlow<Int> = _webrtcPort.asStateFlow()

    val themeMode: StateFlow<Int?> = WenuLinkPreferences.themeFlow

    fun saveMavlinkIp(ip: String) {
        WenuLinkPreferences.saveMavlinkIp(getApplication(), ip)
        _mavlinkIp.value = ip
    }

    fun saveMavlinkPort(port: Int) {
        WenuLinkPreferences.saveMavlinkPort(getApplication(), port)
        _mavlinkPort.value = port
    }

    fun saveWebrtcIp(ip: String) {
        WenuLinkPreferences.saveWebRtcIp(getApplication(), ip)
        _webrtcIp.value = ip
    }

    fun saveWebrtcPort(port: Int) {
        WenuLinkPreferences.saveWebRtcPort(getApplication(), port)
        _webrtcPort.value = port
    }

    fun saveThemeMode(mode: Int) {
        WenuLinkPreferences.saveThemeMode(getApplication(), mode)
    }
}
