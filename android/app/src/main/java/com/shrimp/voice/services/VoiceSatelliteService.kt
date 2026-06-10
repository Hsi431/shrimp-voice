package com.shrimp.voice.services

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Binder
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.shrimp.voice.audio.PcmAudioStreamer
import com.shrimp.voice.audio.WavAudioPlayer
import com.shrimp.voice.network.ConnectionState
import com.shrimp.voice.network.ShrimpProtocol
import com.shrimp.voice.network.ShrimpWebSocketClient
import com.shrimp.voice.notifications.createVoiceSatelliteServiceNotification
import com.shrimp.voice.notifications.createVoiceSatelliteServiceNotificationChannel
import com.shrimp.voice.settings.SettingsStore
import com.shrimp.voice.settings.ShrimpSettings
import com.shrimp.voice.settings.shrimpSettingsStore
import com.shrimp.voice.wakelocks.WifiWakeLock
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import timber.log.Timber

class VoiceSatelliteService : LifecycleService() {

    private val wifiWakeLock = WifiWakeLock()
    private var settingsStore: SettingsStore<ShrimpSettings>? = null

    private var wsClient: ShrimpWebSocketClient? = null
    private var audioStreamer: PcmAudioStreamer? = null
    private var wavAudioPlayer: WavAudioPlayer? = null
    private var lowBandwidthMode = false

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    private val _wsError = MutableStateFlow<String?>(null)
    val wsError: StateFlow<String?> = _wsError.asStateFlow()
    private val _audioDebug = MutableStateFlow(AudioDebug())
    val audioDebug: StateFlow<AudioDebug> = _audioDebug.asStateFlow()
    private val _uiLogs = MutableStateFlow<List<VoiceLogEntry>>(emptyList())
    val uiLogs: StateFlow<List<VoiceLogEntry>> = _uiLogs.asStateFlow()
    private val _voicePhase = MutableStateFlow(VoicePhase.STOPPED)
    val voicePhase: StateFlow<VoicePhase> = _voicePhase.asStateFlow()

    private var isRunning = false
    private var sentAudioBytes = 0L

    // 感測器旁路：proximity + accelerometer 經 WS 文字訊息送到 server（/proximity、/accel 端點用）
    private var sensorManager: SensorManager? = null
    private var sensorsRegistered = false
    @Volatile private var proxValue = -1f
    @Volatile private var proxNear = false
    private var lastAccelSentMs = 0L
    private var accelPeakMag = 0f

    class VoiceSatelliteBinder(val service: VoiceSatelliteService) : Binder()

    data class AudioDebug(
        val sentKb: Long = 0,
        val rms: Int = 0,
        val droppedFrames: Long = 0,
        val lowBandwidthMode: Boolean = false,
        val gateOpen: Boolean = false,
        val suppressedFrames: Long = 0,
        val gateOpenPercent: Int = 0,
        val avgRms: Int = 0,
        val maxRms: Int = 0
    )

    data class VoiceLogEntry(
        val tag: String,
        val msg: String,
        val timestampMs: Long = System.currentTimeMillis()
    )

    enum class VoicePhase(val label: String) {
        STOPPED("未啟動"),
        RECONNECTING("重連中"),
        IDLE("待命中"),
        WAKE("已喚醒"),
        STT("辨識中"),
        THINKING("思考中"),
        SPEAKING("回答中"),
        PLAYING("播放中")
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return VoiceSatelliteBinder(this)
    }

    override fun onCreate() {
        super.onCreate()
        Timber.d("VoiceSatelliteService onCreate")
        try {
            createVoiceSatelliteServiceNotificationChannel(this)

            settingsStore = try {
                shrimpSettingsStore(applicationContext)
            } catch (e: Exception) {
                Timber.e(e, "Failed to init settings store, using defaults")
                null
            }

            wifiWakeLock.create(applicationContext, TAG)

            wavAudioPlayer = WavAudioPlayer(
                context = applicationContext,
                scope = lifecycleScope,
                onPlaybackStateChange = { isPlaying ->
                    // 播放 TTS 時暫停上傳麥克風，避免蝦蝦聽到自己又被喚醒
                    audioStreamer?.setPaused(isPlaying)
                    if (isPlaying) {
                        _voicePhase.value = VoicePhase.PLAYING
                    } else if (_voicePhase.value == VoicePhase.PLAYING && isRunning) {
                        _voicePhase.value = VoicePhase.IDLE
                    }
                },
                onError = { err ->
                    _wsError.value = err
                    updateNotification(_connectionState.value, err)
                }
            )
            Timber.d("Service initialized successfully")
        } catch (e: Exception) {
            Timber.e(e, "Service onCreate failed")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP_MIC) {
            stopVoiceSatellite()
            return START_NOT_STICKY
        }
        if (isRunning) {
            Timber.d("Service already running")
            return START_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                createVoiceSatelliteServiceNotification(this, "正在啟動…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } catch (e: Exception) {
            val errMsg = "前景服務啟動失敗: ${e::class.java.simpleName}: ${e.message ?: e.toString()}"
            _wsError.value = errMsg
            Timber.e(e, errMsg)
            return START_NOT_STICKY
        }
        isRunning = true
        _voicePhase.value = VoicePhase.RECONNECTING
        lifecycleScope.launch { startVoiceSatellite() }
        return START_STICKY
    }

    private suspend fun startVoiceSatellite() {
        try {
            val settings = try {
                settingsStore?.get() ?: ShrimpSettings()
            } catch (e: Exception) {
                Timber.e(e, "Failed to load settings, using defaults")
                ShrimpSettings()
            }
            val host = settings.serverUrl.ifBlank { ShrimpSettings.DEFAULT_SERVER_URL }
            lowBandwidthMode = settings.lowBandwidthMode
            wifiWakeLock.acquire()

            wsClient = ShrimpWebSocketClient(
                host = host,
                authToken = settings.authToken,
                onConnectionStateChange = { state -> handleConnectionState(state) },
                onJsonMessage = { frame -> handleServerFrame(frame) },
                onTtsAudio = { wavData ->
                    Timber.d("Received TTS audio: ${wavData.size} bytes")
                    wavAudioPlayer?.play(wavData)
                },
                onError = { err -> handleWebSocketError(err) }
            )
            wsClient?.connect()
            startAudioStreamer()
            startSensors()
            Timber.d("Voice satellite started successfully, connecting to $host")
        } catch (e: Exception) {
            val errMsg = "Start failed: ${e.message ?: e.toString()}"
            Timber.e(e, errMsg)
            updateNotification(ConnectionState.DISCONNECTED, errMsg)
        }
    }

    fun stopVoiceSatellite() {
        isRunning = false
        stopSensors()
        audioStreamer?.stop()
        audioStreamer = null
        wavAudioPlayer?.release()
        sentAudioBytes = 0L
        _audioDebug.value = AudioDebug()
        _voicePhase.value = VoicePhase.STOPPED
        wsClient?.disconnect()
        wsClient = null
        _connectionState.value = ConnectionState.DISCONNECTED
        wifiWakeLock.release()
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        try { stopSelf() } catch (_: Exception) {}
    }

    private fun handleConnectionState(state: ConnectionState) {
        _connectionState.value = state
        if (state == ConnectionState.CONNECTED) {
            _wsError.value = null
            if (_voicePhase.value == VoicePhase.RECONNECTING || _voicePhase.value == VoicePhase.STOPPED) {
                _voicePhase.value = VoicePhase.IDLE
            }
        } else if (isRunning) {
            _voicePhase.value = VoicePhase.RECONNECTING
        }
        updateNotification(state)
    }

    private fun handleServerFrame(frame: ShrimpProtocol.MessageFrame) {
        Timber.d("[${frame.tag}] ${frame.msg}")
        frame.event?.let { updatePhaseForEvent(it) }
        appendUiLog(frame.tag.ifBlank { "SYS" }, frame.msg)
        // 喚醒後暫時關掉省流量 gate，確保整句話完整上傳
        if (frame.event == "wake_detected" ||
            (frame.tag == "SYS" && frame.msg.contains("已喚醒"))
        ) {
            audioStreamer?.boostFullUpload(WAKE_BOOST_MS)
        }
    }

    private fun updateNotification(state: ConnectionState, errorMsg: String? = null) {
        val content = when (state) {
            ConnectionState.CONNECTED -> "已連線，麥克風串流中"
            ConnectionState.CONNECTING -> "正在連線…"
            ConnectionState.DISCONNECTED ->
                if (errorMsg != null) {
                    if (isRecoverableDisconnect(errorMsg)) errorMsg else "❌ $errorMsg"
                } else {
                    "未連線"
                }
        }
        try {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.notify(NOTIFICATION_ID, createVoiceSatelliteServiceNotification(this, content))
        } catch (e: Exception) {
            val errMsg = "通知更新失敗: ${e::class.java.simpleName}: ${e.message ?: e.toString()}"
            _wsError.value = errMsg
            Timber.e(e, errMsg)
        }
    }

    private fun handleWebSocketError(err: String) {
        if (isRecoverableDisconnect(err)) {
            appendUiLog("SYS", "連線中斷，正在重新連線…")
            _wsError.value = null
            _voicePhase.value = VoicePhase.RECONNECTING
            updateNotification(ConnectionState.DISCONNECTED, "連線中斷，重連中")
            return
        }
        _wsError.value = err
        appendUiLog("ERR", err)
        updateNotification(_connectionState.value, err)
    }

    private fun updatePhaseForEvent(event: String) {
        _voicePhase.value = when (event) {
            "wake_detected" -> VoicePhase.WAKE
            "stt_start" -> VoicePhase.STT
            "stt_end", "agent_start", "thinking_filler" -> VoicePhase.THINKING
            "agent_first_delta", "tts_start", "agent_end" -> VoicePhase.SPEAKING
            "idle" -> VoicePhase.IDLE
            else -> _voicePhase.value
        }
    }

    private fun appendUiLog(tag: String, msg: String) {
        if (msg.isBlank()) return
        val next = _uiLogs.value + VoiceLogEntry(tag = tag, msg = msg)
        _uiLogs.value = next.takeLast(MAX_UI_LOGS)
    }

    private fun isRecoverableDisconnect(err: String): Boolean {
        return err.contains("EOFException", ignoreCase = true) ||
            err.contains("SocketTimeoutException", ignoreCase = true) ||
            err.contains("Socket closed", ignoreCase = true) ||
            err.contains("Canceled", ignoreCase = true) ||
            err.contains("重連", ignoreCase = true)
    }

    private fun startAudioStreamer() {
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                createVoiceSatelliteServiceNotification(this, "正在串流麥克風到伺服器…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } catch (e: Exception) {
            val errMsg = "麥克風前景服務啟動失敗: ${e::class.java.simpleName}: ${e.message ?: e.toString()}"
            _wsError.value = errMsg
            Timber.e(e, errMsg)
            updateNotification(_connectionState.value, errMsg)
            return
        }

        audioStreamer?.stop()
        audioStreamer = PcmAudioStreamer(
            context = applicationContext,
            scope = lifecycleScope,
            onAudioLevel = { rms ->
                _audioDebug.value = _audioDebug.value.copy(rms = rms)
            },
            lowBandwidthMode = lowBandwidthMode,
            onGateStateChange = { isOpen ->
                _audioDebug.value = _audioDebug.value.copy(gateOpen = isOpen)
            },
            onGateMetrics = { metrics ->
                _audioDebug.value = _audioDebug.value.copy(
                    suppressedFrames = metrics.suppressedFrames,
                    gateOpenPercent = metrics.gateOpenPercent,
                    avgRms = metrics.avgRms,
                    maxRms = metrics.maxRms
                )
            },
            onPcmFrame = { pcm ->
                if (wsClient?.sendAudio(pcm) == true) {
                    sentAudioBytes += pcm.size
                    _audioDebug.value = _audioDebug.value.copy(sentKb = sentAudioBytes / 1024)
                } else {
                    _audioDebug.value = _audioDebug.value.copy(
                        droppedFrames = _audioDebug.value.droppedFrames + 1
                    )
                    Timber.w("Dropped PCM frame; WebSocket is not ready")
                }
            },
            onError = { err ->
                _wsError.value = err
                updateNotification(_connectionState.value, err)
            }
        ).also { it.start() }
        _audioDebug.value = _audioDebug.value.copy(lowBandwidthMode = lowBandwidthMode)
    }

    private val proximityListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val v = event.values[0]
            val near = v < minOf(event.sensor.maximumRange, 5f)
            proxValue = v
            if (near != proxNear) {
                proxNear = near
                sendSensorFrame(includeAccel = false)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val accelListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            val mag = sqrt(x * x + y * y + z * z)
            if (mag > accelPeakMag) accelPeakMag = mag
            val now = System.currentTimeMillis()
            if (now - lastAccelSentMs < ACCEL_SEND_INTERVAL_MS) return
            lastAccelSentMs = now
            sendSensorFrame(includeAccel = true, ax = x, ay = y, az = z, aMag = accelPeakMag)
            accelPeakMag = 0f
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun sendSensorFrame(
        includeAccel: Boolean,
        ax: Float = 0f,
        ay: Float = 0f,
        az: Float = 0f,
        aMag: Float = 0f
    ) {
        val client = wsClient ?: return
        try {
            val json = JSONObject().put("type", "sensor")
            if (proxValue >= 0f) {
                json.put("prox", proxValue.toDouble())
                json.put("proxNear", proxNear)
            }
            if (includeAccel) {
                json.put("ax", ax.toDouble())
                json.put("ay", ay.toDouble())
                json.put("az", az.toDouble())
                json.put("aMag", aMag.toDouble())
            }
            client.sendText(json.toString())
        } catch (e: Exception) {
            Timber.w(e, "Failed to send sensor frame")
        }
    }

    private fun startSensors() {
        if (sensorsRegistered) return
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager = sm
        try {
            sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let {
                sm.registerListener(proximityListener, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
        } catch (e: Exception) {
            Timber.e(e, "[sensor] proximity register FAILED")
        }
        try {
            sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                // FASTEST：敲擊脈衝只有 5~10ms，50Hz(GAME) 會直接漏採峰值；
                // >200Hz 需要 HIGH_SAMPLING_RATE_SENSORS 權限（manifest 已加）。
                // 上傳仍由 ACCEL_SEND_INTERVAL_MS 節流（區間取峰），頻寬不變
                sm.registerListener(accelListener, it, SensorManager.SENSOR_DELAY_FASTEST)
                Timber.i("[sensor] accelerometer registered at FASTEST")
            }
        } catch (e: Exception) {
            Timber.e(e, "[sensor] accelerometer FASTEST register FAILED, falling back to GAME")
            try {
                sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                    sm.registerListener(accelListener, it, SensorManager.SENSOR_DELAY_GAME)
                }
            } catch (e2: Exception) {
                Timber.e(e2, "[sensor] accelerometer register FAILED")
            }
        }
        sensorsRegistered = true
    }

    private fun stopSensors() {
        if (!sensorsRegistered) return
        sensorManager?.let { sm ->
            sm.unregisterListener(proximityListener)
            sm.unregisterListener(accelListener)
        }
        sensorsRegistered = false
    }

    override fun onDestroy() {
        Timber.d("VoiceSatelliteService onDestroy")
        isRunning = false
        stopSensors()
        audioStreamer?.stop()
        audioStreamer = null
        wavAudioPlayer?.release()
        sentAudioBytes = 0L
        _audioDebug.value = AudioDebug()
        _voicePhase.value = VoicePhase.STOPPED
        wsClient?.disconnect()
        wsClient = null
        wifiWakeLock.release()
        super.onDestroy()
    }

    companion object {
        const val TAG = "ShrimpVoiceSatellite"
        const val NOTIFICATION_ID = 42
        const val ACTION_STOP_MIC = "com.shrimp.voice.action.STOP_MIC"
        private const val MAX_UI_LOGS = 80
        private const val WAKE_BOOST_MS = 12_000L
        // server 端 accel ring buffer 為 300 筆；~30Hz 上傳對應約 10 秒視窗，
        // 區間內取峰值避免敲擊瞬間被節流吃掉
        private const val ACCEL_SEND_INTERVAL_MS = 33L

        fun start(context: Context) {
            try {
                val intent = Intent(context, VoiceSatelliteService::class.java)
                context.startForegroundService(intent)
            } catch (e: Exception) {
                Timber.e(e, "Failed to start service")
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, VoiceSatelliteService::class.java)
                context.stopService(intent)
            } catch (_: Exception) {}
        }
    }
}
