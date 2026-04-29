package org.WenuLink

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

private fun SharedPreferences.getStringOrDefault(key: String, default: String): String =
    getString(key, default) ?: default

object WenuLinkPreferences {
    private const val PREFS_NAME = "wenulink_config"

    private const val KEY_MAVLINK_IP = "mavlink_ip"
    private const val KEY_MAVLINK_PORT = "mavlink_port"
    private const val KEY_WEBRTC_IP = "webrtc_ip"
    private const val KEY_WEBRTC_PORT = "webrtc_port"
    private const val KEY_THEME = "app_theme_mode"

    private const val MAVLINK_DEFAULT_IP = "192.168.1.220"
    private const val MAVLINK_DEFAULT_PORT = 14550
    private const val WEBRTC_DEFAULT_IP = "192.168.1.100"
    private const val WEBRTC_DEFAULT_PORT = 8090

    private val _themeFlow = MutableStateFlow<Int?>(null)
    val themeFlow = _themeFlow.asStateFlow()

    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getMavlinkIp(context: Context): String =
        getPrefs(context).getStringOrDefault(KEY_MAVLINK_IP, MAVLINK_DEFAULT_IP)

    fun saveMavlinkIp(context: Context, ip: String) =
        getPrefs(context).edit { putString(KEY_MAVLINK_IP, ip) }

    fun getMavlinkPort(context: Context): Int =
        getPrefs(context).getInt(KEY_MAVLINK_PORT, MAVLINK_DEFAULT_PORT)

    fun saveMavlinkPort(context: Context, port: Int) =
        getPrefs(context).edit { putInt(KEY_MAVLINK_PORT, port) }

    fun getWebRtcIp(context: Context): String =
        getPrefs(context).getStringOrDefault(KEY_WEBRTC_IP, WEBRTC_DEFAULT_IP)

    fun saveWebRtcIp(context: Context, ip: String) =
        getPrefs(context).edit { putString(KEY_WEBRTC_IP, ip) }

    fun getWebRtcPort(context: Context): Int =
        getPrefs(context).getInt(KEY_WEBRTC_PORT, WEBRTC_DEFAULT_PORT)

    fun saveWebRtcPort(context: Context, port: Int) =
        getPrefs(context).edit { putInt(KEY_WEBRTC_PORT, port) }

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
