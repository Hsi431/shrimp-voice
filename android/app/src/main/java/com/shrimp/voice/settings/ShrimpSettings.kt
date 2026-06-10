package com.shrimp.voice.settings

import android.content.Context
import kotlinx.serialization.Serializable

@Serializable
data class ShrimpSettings(
    val serverUrl: String = DEFAULT_SERVER_URL,
    val sessionId: String = "",
    val autoStart: Boolean = false,
    val lowBandwidthMode: Boolean = false,
    val authToken: String = ""
) {
    companion object {
        const val DEFAULT_SERVER_URL = "192.168.1.100:8443"
    }
}

private val lock = Any()

@Volatile
private var _instance: SettingsStore<ShrimpSettings>? = null

fun shrimpSettingsStore(context: Context): SettingsStore<ShrimpSettings> {
    return _instance ?: synchronized(lock) {
        _instance ?: SettingsStoreImpl(
            default = ShrimpSettings(),
            produceFile = { context.applicationContext.filesDir.resolve("shrimp_settings.pb") },
            serializer = ShrimpSettings.serializer()
        ).also { _instance = it }
    }
}
