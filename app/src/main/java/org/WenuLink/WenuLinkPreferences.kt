package org.WenuLink

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object WenuLinkPreferences {
    private const val PREFS_NAME = "wenulink_config"
    private const val KEY_MAVLINK_GCS_ADDR = "mavlink_gcs_addr"
    private const val KEY_WEBRTC_SIGNAL_ADDR = "webrtc_signal_addr"
    private const val KEY_THEME = "app_theme_mode"

    private val _themeFlow = MutableStateFlow<Int?>(null)
    val themeFlow = _themeFlow.asStateFlow()

    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getMavlinkIp(context: Context): String =
        getPrefs(context).getString(KEY_MAVLINK_GCS_ADDR, "192.168.1.220:14550")
            ?: "192.168.1.220:14550"

    fun saveMavlinkIp(context: Context, ip: String) =
        getPrefs(context).edit().putString(KEY_MAVLINK_GCS_ADDR, ip).apply()

    fun getWebRtcIp(context: Context): String =
        getPrefs(context).getString(KEY_WEBRTC_SIGNAL_ADDR, "192.168.1.100:8090")
            ?: "192.168.1.100:8090"

    fun saveWebRtcIp(context: Context, ip: String) =
        getPrefs(context).edit().putString(KEY_WEBRTC_SIGNAL_ADDR, ip).apply()

    fun getThemeMode(context: Context): Int {
        val mode = getPrefs(context).getInt(KEY_THEME, 0)
        if (_themeFlow.value == null) {
            _themeFlow.value = mode
        }
        return mode
    }

    fun saveThemeMode(context: Context, mode: Int) {
        getPrefs(context).edit().putInt(KEY_THEME, mode).apply()
        _themeFlow.value = mode
    }
}
