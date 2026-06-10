package com.shrimp.voice.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

class PcmAudioStreamer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onPcmFrame: (ByteArray) -> Unit,
    private val onAudioLevel: (Int) -> Unit = {},
    private val lowBandwidthMode: Boolean = false,
    private val onGateStateChange: (Boolean) -> Unit = {},
    private val onGateMetrics: (GateMetrics) -> Unit = {},
    private val onError: (String) -> Unit
) {
    private var job: Job? = null
    private var audioRecord: AudioRecord? = null
    @Volatile private var isPaused = false
    @Volatile private var forceUploadUntilMs = 0L
    // 播放音效暫停上傳期間先暫存麥克風，恢復後補送——防止使用者搶在「在！」播完前講話被吃字
    private val pauseBuffer = ArrayDeque<ByteArray>()

    data class GateMetrics(
        val totalFrames: Long = 0,
        val sentFrames: Long = 0,
        val suppressedFrames: Long = 0,
        val gateOpenPercent: Int = 0,
        val avgRms: Int = 0,
        val maxRms: Int = 0
    )

    fun start() {
        if (job?.isActive == true) return
        if (!hasRecordAudioPermission()) {
            onError("缺少 RECORD_AUDIO 權限，無法串流麥克風")
            return
        }

        job = scope.launch(Dispatchers.IO) {
            runStreamer()
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        release()
    }

    fun setPaused(paused: Boolean) {
        isPaused = paused
    }

    fun boostFullUpload(durationMs: Long) {
        forceUploadUntilMs = System.currentTimeMillis() + durationMs
        Timber.i("[voice-gate] boostFullUpload durationMs=%d", durationMs)
    }

    @SuppressLint("MissingPermission")
    private fun runStreamer() {
        try {
            val minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                onError("AudioRecord 不支援 16kHz mono PCM16")
                return
            }

            val bufferSize = maxOf(minBufferSize, FRAME_BYTES * 4)
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            audioRecord = recorder

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                onError("AudioRecord 初始化失敗")
                release()
                return
            }

            val frame = ByteArray(FRAME_BYTES)
            val gate = RmsUploadGate()
            val metrics = GateMetricsAccumulator(lowBandwidthMode)
            recorder.startRecording()
            Timber.d("PCM streamer started: sampleRate=$SAMPLE_RATE frameBytes=$FRAME_BYTES lowBandwidth=$lowBandwidthMode")

            while (job?.isActive == true) {
                var offset = 0
                while (offset < frame.size && job?.isActive == true) {
                    val read = recorder.read(frame, offset, frame.size - offset, AudioRecord.READ_BLOCKING)
                    if (read <= 0) break
                    offset += read
                }
                if (offset == frame.size) {
                    val rms = calculateRms(frame)
                    onAudioLevel(rms)
                    if (isPaused) {
                        synchronized(pauseBuffer) {
                            pauseBuffer.addLast(frame.copyOf())
                            while (pauseBuffer.size > PAUSE_BUFFER_FRAMES) pauseBuffer.removeFirst()
                        }
                    } else {
                        val backlog = synchronized(pauseBuffer) {
                            if (pauseBuffer.isEmpty()) emptyList() else pauseBuffer.toList().also { pauseBuffer.clear() }
                        }
                        if (backlog.isNotEmpty()) {
                            Timber.i("[voice-gate] flushing %d paused frames", backlog.size)
                            backlog.forEach(onPcmFrame)
                        }
                        val forceUpload = System.currentTimeMillis() < forceUploadUntilMs
                        if (lowBandwidthMode && !forceUpload) {
                            // 開關門用 64ms 子視窗峰值：短音節（蝦蝦）能量集中，
                            // 256ms 整段平均會被靜音稀釋導致輕聲喊不開門
                            val gateLevel = calculateMaxSubRms(frame)
                            val wasOpen = gate.isOpen
                            val framesToSend = gate.accept(frame, gateLevel)
                            framesToSend.forEach(onPcmFrame)
                            if (wasOpen != gate.isOpen) {
                                Timber.i(
                                    "[voice-gate] transition=%s gateLevel=%d frameRms=%d preRollFrames=%d",
                                    if (gate.isOpen) "open" else "closed",
                                    gateLevel,
                                    rms,
                                    framesToSend.size
                                )
                            }
                            metrics.record(gateLevel, gate.isOpen, framesToSend.size)
                            metrics.maybeSnapshot()?.let(onGateMetrics)
                            onGateStateChange(gate.isOpen)
                        } else {
                            onPcmFrame(frame.copyOf())
                            metrics.record(rms, isGateOpen = true, sentFrameCount = 1)
                            metrics.maybeSnapshot()?.let(onGateMetrics)
                            if (forceUpload) onGateStateChange(true)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            onError("麥克風串流失敗: ${e::class.java.simpleName}: ${e.message ?: e.toString()}")
            Timber.e(e, "PCM streamer failed")
        } finally {
            release()
        }
    }

    private fun release() {
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** 把 frame 切成 SUB_WINDOWS 段，回傳最大段的 RMS。 */
    private fun calculateMaxSubRms(frame: ByteArray): Int {
        val subBytes = frame.size / SUB_WINDOWS
        var maxRms = 0
        for (w in 0 until SUB_WINDOWS) {
            var sum = 0.0
            var count = 0
            var i = w * subBytes
            val end = minOf(i + subBytes, frame.size)
            while (i + 1 < end) {
                val sample = ((frame[i + 1].toInt() shl 8) or (frame[i].toInt() and 0xff)).toShort().toInt()
                sum += sample.toDouble() * sample.toDouble()
                count++
                i += 2
            }
            if (count > 0) {
                val rms = kotlin.math.sqrt(sum / count).toInt()
                if (rms > maxRms) maxRms = rms
            }
        }
        return maxRms
    }

    private fun calculateRms(frame: ByteArray): Int {
        var sum = 0.0
        var count = 0
        var i = 0
        while (i + 1 < frame.size) {
            val sample = ((frame[i + 1].toInt() shl 8) or (frame[i].toInt() and 0xff)).toShort().toInt()
            sum += sample.toDouble() * sample.toDouble()
            count++
            i += 2
        }
        return if (count == 0) 0 else kotlin.math.sqrt(sum / count).toInt()
    }

    private class RmsUploadGate {
        private val preRollFrames = ArrayDeque<ByteArray>()
        private var silentFrames = 0
        private var loudFrames = 0
        var isOpen = false
            private set

        fun accept(frame: ByteArray, rms: Int): List<ByteArray> {
            val current = frame.copyOf()
            pushPreRoll(current)

            if (rms >= OPEN_RMS_THRESHOLD) {
                loudFrames++
                silentFrames = 0
                if (!isOpen && loudFrames < OPEN_LOUD_FRAMES) return emptyList()
                return if (!isOpen) {
                    isOpen = true
                    preRollFrames.toList()
                } else {
                    listOf(current)
                }
            }

            loudFrames = 0
            if (!isOpen) return emptyList()

            silentFrames++
            if (silentFrames >= CLOSE_SILENT_FRAMES) {
                isOpen = false
                silentFrames = 0
                return emptyList()
            }
            return listOf(current)
        }

        private fun pushPreRoll(frame: ByteArray) {
            preRollFrames.addLast(frame)
            while (preRollFrames.size > PRE_ROLL_FRAMES) {
                preRollFrames.removeFirst()
            }
        }

        companion object {
            // 門檻比對的是 64ms 子視窗峰值 RMS（calculateMaxSubRms）。
            // S23 實測：噪音底 frame-avg ~158，喊「蝦蝦」frame-avg 638~907；
            // 子視窗峰值約為 frame-avg 的 2~3 倍，700 對輕聲喊仍有餘裕
            private const val OPEN_RMS_THRESHOLD = 700
            private const val OPEN_LOUD_FRAMES = 1
            private const val PRE_ROLL_FRAMES = 6
            private const val CLOSE_SILENT_FRAMES = 8
        }
    }

    private class GateMetricsAccumulator(private val lowBandwidthMode: Boolean) {
        private var totalFrames = 0L
        private var sentFrames = 0L
        private var suppressedFrames = 0L
        private var gateOpenFrames = 0L
        private var rmsSum = 0L
        private var maxRms = 0
        private var lastSnapshotMs = System.currentTimeMillis()

        fun record(rms: Int, isGateOpen: Boolean, sentFrameCount: Int) {
            totalFrames++
            sentFrames += sentFrameCount.toLong()
            if (sentFrameCount == 0) suppressedFrames++
            if (isGateOpen) gateOpenFrames++
            rmsSum += rms.toLong()
            if (rms > maxRms) maxRms = rms
        }

        fun maybeSnapshot(): GateMetrics? {
            val now = System.currentTimeMillis()
            if (now - lastSnapshotMs < METRICS_INTERVAL_MS) return null
            lastSnapshotMs = now
            val gateOpenPercent = if (totalFrames == 0L) 0 else ((gateOpenFrames * 100) / totalFrames).toInt()
            val avgRms = if (totalFrames == 0L) 0 else (rmsSum / totalFrames).toInt()
            val snapshot = GateMetrics(
                totalFrames = totalFrames,
                sentFrames = sentFrames,
                suppressedFrames = suppressedFrames,
                gateOpenPercent = gateOpenPercent,
                avgRms = avgRms,
                maxRms = maxRms
            )
            Timber.i(
                "[voice-gate] mode=%s totalFrames=%d sentFrames=%d suppressedFrames=%d gateOpenPct=%d avgRms=%d maxRms=%d",
                if (lowBandwidthMode) "low_bandwidth" else "always_on",
                totalFrames,
                sentFrames,
                suppressedFrames,
                gateOpenPercent,
                avgRms,
                maxRms
            )
            return snapshot
        }

        companion object {
            private const val METRICS_INTERVAL_MS = 5_000L
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val BYTES_PER_SAMPLE = 2
        private const val FRAME_SAMPLES = 4_096
        private const val FRAME_BYTES = FRAME_SAMPLES * BYTES_PER_SAMPLE
        // 1.5 秒：蓋過「在！」播放長度；太長會把長回覆的喇叭回音整段補送回伺服器
        private const val PAUSE_BUFFER_FRAMES = 6
        // gate 開關門判斷的子視窗數（256ms frame ÷ 4 = 64ms）
        private const val SUB_WINDOWS = 4
    }
}
