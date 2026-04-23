package org.WenuLink

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object WenuLinkPreferences {
    private const val PREFS_NAME = "wenulink_config"
    private const val KEY_MAVLINK_GCS_ADDRESS = "mavlink_gcs_address"
    private const val KEY_WEBRTC_SIGNAL_ADDRESS = "webrtc_signal_address"
    private const val KEY_THEME = "app_theme_mode"

    private const val MAVLINK_DEFAULT_ADDRESS = "192.168.1.220:14550"
    private const val WEBRTC_DEFAULT_ADDRESS = "192.168.1.100:8090"

    private val _themeFlow = MutableStateFlow<Int?>(null)
    val themeFlow = _themeFlow.asStateFlow()

    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun SharedPreferences.getStringOrDefault(key: String, default: String): String =
        getString(key, default) ?: default

    fun getMavlinkIp(context: Context): String =
        getPrefs(context).getStringOrDefault(KEY_MAVLINK_GCS_ADDRESS, MAVLINK_DEFAULT_ADDRESS)

    fun saveMavlinkIp(context: Context, ip: String) =
        getPrefs(context).edit { putString(KEY_MAVLINK_GCS_ADDRESS, ip) }

    fun getWebRtcIp(context: Context): String =
        getPrefs(context).getStringOrDefault(KEY_WEBRTC_SIGNAL_ADDRESS, WEBRTC_DEFAULT_ADDRESS)

    fun saveWebRtcIp(context: Context, ip: String) =
        getPrefs(context).edit { putString(KEY_WEBRTC_SIGNAL_ADDRESS, ip) }

    fun getThemeMode(context: Context): Int {
        val mode = getPrefs(context).getInt(KEY_THEME, 0)
        if (_themeFlow.value == null) {
            _themeFlow.value = mode
        }
        return mode
    }

    fun saveThemeMode(context: Context, mode: Int) {
        getPrefs(context).edit { putInt(KEY_THEME, mode) }
        _themeFlow.value = mode
    }
}
