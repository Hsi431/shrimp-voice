package com.shrimp.voice.settings

import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.KSerializer
import timber.log.Timber
import java.io.File

interface SettingsStore<T> {
    fun <R> getFlow(transform: T.() -> R): Flow<R>
    suspend fun get(): T
    suspend fun update(transform: suspend (T) -> T)
}

class SettingsStoreImpl<T>(
    private val default: T,
    produceFile: () -> File,
    serializer: KSerializer<T>
) : SettingsStore<T> {
    private val dataStore = DataStoreFactory.create(
        serializer = SettingsSerializer(serializer, default),
        corruptionHandler = null,
        produceFile = produceFile
    )

    override fun <R> getFlow(transform: T.() -> R) = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading settings, returning defaults")
                emit(default)
            } else throw exception
        }
        .map(transform)
        .distinctUntilChanged()
        .onEach { Timber.d("Loaded settings: $it") }

    override suspend fun get(): T = getFlow { this }.first()

    override suspend fun update(transform: suspend (T) -> T) {
        dataStore.updateData(transform)
    }
}
