package com.shrimp.voice.settings

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

fun <T> defaultCorruptionHandler(default: T): (CorruptionException) -> T = { exception ->
    throw exception // re-throw; DataStore will replace with default via catch
}

class SettingsSerializer<T>(
    private val serializer: kotlinx.serialization.KSerializer<T>,
    private val default: T
) : Serializer<T> {
    private val json = Json { ignoreUnknownKeys = true }

    override val defaultValue: T
        get() = default

    override suspend fun readFrom(input: java.io.InputStream): T {
        return try {
            json.decodeFromString(serializer, input.readBytes().decodeToString())
        } catch (e: SerializationException) {
            throw CorruptionException("Cannot read settings", e)
        }
    }

    override suspend fun writeTo(t: T, output: java.io.OutputStream) {
        output.write(json.encodeToString(serializer, t).encodeToByteArray())
    }
}
