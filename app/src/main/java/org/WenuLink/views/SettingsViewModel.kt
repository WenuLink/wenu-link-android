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

    private val _webrtcIp = MutableStateFlow(WenuLinkPreferences.getWebRtcIp(application))
    val webrtcIp: StateFlow<String> = _webrtcIp.asStateFlow()

    val themeMode: StateFlow<Int?> = WenuLinkPreferences.themeFlow

    fun saveMavlinkIp(ip: String) {
        WenuLinkPreferences.saveMavlinkIp(getApplication(), ip)
        _mavlinkIp.value = ip
    }

    fun saveWebrtcIp(ip: String) {
        WenuLinkPreferences.saveWebRtcIp(getApplication(), ip)
        _webrtcIp.value = ip
    }

    fun saveThemeMode(mode: Int) {
        WenuLinkPreferences.saveThemeMode(getApplication(), mode)
    }
}
