package com.shrimp.voice.audio

import android.content.Context
import android.media.MediaPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

class WavAudioPlayer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onPlaybackStateChange: (Boolean) -> Unit = {},
    private val onError: (String) -> Unit
) {
    private var player: MediaPlayer? = null
    private var currentFile: File? = null
    private val playQueue = Channel<ByteArray>(capacity = Channel.UNLIMITED)
    private var queueJob: Job? = null
    private var pendingCount = 0

    fun play(wavData: ByteArray) {
        ensureQueue()
        if (playQueue.trySend(wavData).isSuccess) {
            pendingCount++
        }
    }

    private fun ensureQueue() {
        if (queueJob?.isActive == true) return
        queueJob = scope.launch {
            for (wavData in playQueue) {
                playOne(wavData)
            }
        }
    }

    private suspend fun playOne(wavData: ByteArray) {
        var wavFile: File? = null
        try {
            wavFile = withContext(Dispatchers.IO) {
                File.createTempFile("shrimp_tts_", ".wav", context.cacheDir).apply {
                    writeBytes(wavData)
                }
            }

            kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                player?.release()
                currentFile?.delete()
                currentFile = wavFile
                player = MediaPlayer().apply {
                    setDataSource(wavFile.absolutePath)
                    setOnCompletionListener {
                        it.release()
                        if (player === it) player = null
                        if (currentFile === wavFile) currentFile = null
                        wavFile.delete()
                        if (continuation.isActive) continuation.resume(Unit) { _, _, _ -> }
                    }
                    setOnErrorListener { mp, what, extra ->
                        mp.release()
                        if (player === mp) player = null
                        if (currentFile === wavFile) currentFile = null
                        wavFile.delete()
                        onError("播放 TTS WAV 失敗: what=$what extra=$extra")
                        if (continuation.isActive) continuation.resume(Unit) { _, _, _ -> }
                        true
                    }
                    prepare()
                    onPlaybackStateChange(true)
                    start()
                }
                continuation.invokeOnCancellation {
                    try { player?.release() } catch (_: Exception) {}
                    player = null
                    wavFile.delete()
                }
            }
        } catch (e: Exception) {
            wavFile?.delete()
            onError("播放 TTS WAV 失敗: ${e::class.java.simpleName}: ${e.message ?: e.toString()}")
            Timber.e(e, "Failed to play WAV")
        } finally {
            pendingCount = (pendingCount - 1).coerceAtLeast(0)
            if (pendingCount == 0) {
                onPlaybackStateChange(false)
            }
        }
    }

    fun release() {
        queueJob?.cancel()
        queueJob = null
        pendingCount = 0
        while (playQueue.tryReceive().isSuccess) {}
        onPlaybackStateChange(false)
        try { player?.release() } catch (_: Exception) {}
        player = null
        currentFile?.delete()
        currentFile = null
    }
}
