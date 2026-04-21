package org.WenuLink.views

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.WenuLink.ui.utils.PrefsManager

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val _mavlinkIp = MutableStateFlow(PrefsManager.getMavlinkIp(application))
    val mavlinkIp: StateFlow<String> = _mavlinkIp.asStateFlow()

    private val _webrtcIp = MutableStateFlow(PrefsManager.getWebRtcIp(application))
    val webrtcIp: StateFlow<String> = _webrtcIp.asStateFlow()

    val themeMode: StateFlow<Int?> = PrefsManager.themeFlow

    fun saveMavlinkIp(ip: String) {
        PrefsManager.saveMavlinkIp(getApplication(), ip)
        _mavlinkIp.value = ip
    }

    fun saveWebrtcIp(ip: String) {
        PrefsManager.saveWebRtcIp(getApplication(), ip)
        _webrtcIp.value = ip
    }

    fun saveThemeMode(mode: Int) {
        PrefsManager.saveThemeMode(getApplication(), mode)
    }
}
