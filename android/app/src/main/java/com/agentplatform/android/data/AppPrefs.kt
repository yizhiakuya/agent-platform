package com.agentplatform.android.data

import android.content.Context

/**
 * Tiny SharedPreferences wrapper for the only state the device app needs:
 * which server it's bound to and the long-lived device JWT issued by
 * {@code POST /api/auth/redeem/{token}}.
 *
 * <p>This is plain app-private SharedPreferences. A rooted device can still
 * read it, so do not treat the device JWT as hardware-backed secret storage.
 */
class AppPrefs(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var serverUrl: String?
        get() = prefs.getString(KEY_SERVER, null)
        set(value) { prefs.edit().putString(KEY_SERVER, value).apply() }

    var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) { prefs.edit().putString(KEY_TOKEN, value).apply() }

    var deviceId: String?
        get() = prefs.getString(KEY_DEVICE, null)
        set(value) { prefs.edit().putString(KEY_DEVICE, value).apply() }

    var autoApproveUiTools: Boolean
        get() = prefs.getBoolean(KEY_AUTO_APPROVE_UI_TOOLS, false)
        set(value) { prefs.edit().putBoolean(KEY_AUTO_APPROVE_UI_TOOLS, value).apply() }

    fun isBound(): Boolean = !serverUrl.isNullOrBlank() && !token.isNullOrBlank()

    fun save(serverUrl: String, token: String, deviceId: String) {
        prefs.edit()
            .putString(KEY_SERVER, serverUrl)
            .putString(KEY_TOKEN, token)
            .putString(KEY_DEVICE, deviceId)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val NAME = "agent.prefs"
        private const val KEY_SERVER = "serverUrl"
        private const val KEY_TOKEN = "token"
        private const val KEY_DEVICE = "deviceId"
        private const val KEY_AUTO_APPROVE_UI_TOOLS = "autoApproveUiTools"
    }
}
