package com.flux.codebuddy

import android.content.Context

data class ServerConfig(
    val url: String,
    val displayName: String,
    val remember: Boolean,
)

class ServerStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): ServerConfig? {
        if (!prefs.getBoolean(KEY_REMEMBER, false)) return null
        val url = prefs.getString(KEY_URL, null)?.takeIf { it.isNotBlank() } ?: return null
        return ServerConfig(
            url = url,
            displayName = prefs.getString(KEY_NAME, "").orEmpty(),
            remember = true,
        )
    }

    /** UI size is a display preference, independent of the remembered server. */
    fun loadScale(): Float = prefs.getFloat(KEY_UI_SCALE, UiScale.DEFAULT)

    fun saveScale(scale: Float) {
        prefs.edit().putFloat(KEY_UI_SCALE, UiScale.clamp(scale)).apply()
    }

    fun save(config: ServerConfig) {
        prefs.edit().apply {
            if (config.remember) {
                putString(KEY_URL, config.url)
                putString(KEY_NAME, config.displayName)
                putBoolean(KEY_REMEMBER, true)
            } else {
                remove(KEY_URL)
                remove(KEY_NAME)
                putBoolean(KEY_REMEMBER, false)
            }
        }.apply()
    }

    private companion object {
        const val PREFS_NAME = "codebuddy_mobile"
        const val KEY_URL = "server_url"
        const val KEY_NAME = "display_name"
        const val KEY_REMEMBER = "remember_server"
        const val KEY_UI_SCALE = "ui_scale"
    }
}
