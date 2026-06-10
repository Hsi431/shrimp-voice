package com.shrimp.voice.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import timber.log.Timber
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shrimp.voice.network.ConnectionState
import com.shrimp.voice.services.VoiceSatelliteService
import com.shrimp.voice.settings.ShrimpSettings
import com.shrimp.voice.settings.shrimpSettingsStore
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsStore = remember { shrimpSettingsStore(context) }

    var serverUrl by remember { mutableStateOf(ShrimpSettings.DEFAULT_SERVER_URL) }
    var sessionId by remember { mutableStateOf("") }
    var authToken by remember { mutableStateOf("") }
    var isServiceRunning by remember { mutableStateOf(false) }
    var connectionState by remember { mutableStateOf(ConnectionState.DISCONNECTED) }
    var lastError by remember { mutableStateOf<String?>(null) }
    var saveMessage by remember { mutableStateOf<String?>(null) }
    var sentAudioKb by remember { mutableLongStateOf(0L) }
    var audioRms by remember { mutableIntStateOf(0) }
    var droppedFrames by remember { mutableLongStateOf(0L) }
    var lowBandwidthMode by remember { mutableStateOf(false) }
    var runtimeLowBandwidthMode by remember { mutableStateOf(false) }
    var gateOpen by remember { mutableStateOf(false) }
    var suppressedFrames by remember { mutableLongStateOf(0L) }
    var gateOpenPercent by remember { mutableIntStateOf(0) }
    var avgRms by remember { mutableIntStateOf(0) }
    var maxRms by remember { mutableIntStateOf(0) }
    var voiceLogs by remember { mutableStateOf<List<VoiceSatelliteService.VoiceLogEntry>>(emptyList()) }
    var voicePhase by remember { mutableStateOf(VoiceSatelliteService.VoicePhase.STOPPED) }
    var isBound by remember { mutableStateOf(false) }
    var ignoresBatteryOptimizations by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }

    // Load saved settings
    LaunchedEffect(Unit) {
        val settings = settingsStore.get()
        serverUrl = settings.serverUrl
        sessionId = settings.sessionId
        authToken = settings.authToken
        lowBandwidthMode = settings.lowBandwidthMode
    }

    // Bind to service
    val serviceConnection = remember {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val serviceBinder = binder as VoiceSatelliteService.VoiceSatelliteBinder
                scope.launch {
                    serviceBinder.service.connectionState.collect { state ->
                        connectionState = state
                        isServiceRunning = true
                    }
                }
                scope.launch {
                    serviceBinder.service.wsError.collect { err ->
                        lastError = err
                    }
                }
                scope.launch {
                    serviceBinder.service.audioDebug.collect { debug ->
                        sentAudioKb = debug.sentKb
                        audioRms = debug.rms
                        droppedFrames = debug.droppedFrames
                        runtimeLowBandwidthMode = debug.lowBandwidthMode
                        gateOpen = debug.gateOpen
                        suppressedFrames = debug.suppressedFrames
                        gateOpenPercent = debug.gateOpenPercent
                        avgRms = debug.avgRms
                        maxRms = debug.maxRms
                    }
                }
                scope.launch {
                    serviceBinder.service.uiLogs.collect { logs ->
                        voiceLogs = logs
                    }
                }
                scope.launch {
                    serviceBinder.service.voicePhase.collect { phase ->
                        voicePhase = phase
                    }
                }
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                isServiceRunning = false
                connectionState = ConnectionState.DISCONNECTED
                voicePhase = VoiceSatelliteService.VoicePhase.STOPPED
            }
        }
    }

    DisposableEffect(context) {
        isBound = context.bindService(
            Intent(context, VoiceSatelliteService::class.java),
            serviceConnection,
            0
        )
        onDispose {
            if (isBound) {
                try { context.unbindService(serviceConnection) } catch (_: Exception) {}
                isBound = false
            }
        }
    }

    fun startService() {
        VoiceSatelliteService.start(context)
        if (!isBound) {
            isBound = context.bindService(
                Intent(context, VoiceSatelliteService::class.java),
                serviceConnection,
                Context.BIND_AUTO_CREATE
            )
        }
    }

    fun stopService() {
        VoiceSatelliteService.stop(context)
        if (isBound) {
            try { context.unbindService(serviceConnection) } catch (_: Exception) {}
            isBound = false
        }
        isServiceRunning = false
        connectionState = ConnectionState.DISCONNECTED
        voicePhase = VoiceSatelliteService.VoicePhase.STOPPED
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "蝦蝦語音",
            style = MaterialTheme.typography.headlineMedium
        )

        VoiceHeroCard(
            phase = voicePhase,
            isServiceRunning = isServiceRunning,
            connectionState = connectionState
        )

        // Connection status card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = when (connectionState) {
                    ConnectionState.CONNECTED -> Color(0xFF4CAF50).copy(alpha = 0.15f)
                    ConnectionState.CONNECTING -> Color(0xFFFFC107).copy(alpha = 0.15f)
                    ConnectionState.DISCONNECTED -> Color(0xFF757575).copy(alpha = 0.15f)
                }
            )
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "•",
                        color = when (connectionState) {
                            ConnectionState.CONNECTED -> Color(0xFF4CAF50)
                            ConnectionState.CONNECTING -> Color(0xFFFFC107)
                            ConnectionState.DISCONNECTED -> Color(0xFF757575)
                        },
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when (connectionState) {
                            ConnectionState.CONNECTED -> "已連線"
                            ConnectionState.CONNECTING -> "連線中…"
                            ConnectionState.DISCONNECTED ->
                                if (isServiceRunning) "斷線（自動重連中）" else "未啟動"
                        },
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                if (isServiceRunning) {
                    Text(
                        text = if (connectionState == ConnectionState.CONNECTED)
                            "麥克風串流中：音訊會持續送到伺服器做「蝦蝦」喚醒詞偵測。"
                        else
                            "服務已啟動；等待重新連線後才會送出麥克風音訊。",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (connectionState == ConnectionState.CONNECTED)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "音訊偵錯：已送出 ${sentAudioKb} KB，音量 RMS $audioRms，丟棄 $droppedFrames frames",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Gate 統計：省略 $suppressedFrames frames，開啟 $gateOpenPercent%，平均 RMS $avgRms，峰值 $maxRms",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = streamModeStatusText(
                            configuredLowBandwidth = lowBandwidthMode,
                            runtimeLowBandwidth = runtimeLowBandwidthMode,
                            gateOpen = gateOpen,
                            isServiceRunning = isServiceRunning
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Connection error
        if (lastError != null) {
            Text(
                text = "❌ $lastError",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth()
            )
        }

        VoiceTimelineCard(
            logs = voiceLogs,
            isServiceRunning = isServiceRunning,
            connectionState = connectionState,
            phase = voicePhase
        )

        BatteryOptimizationCard(
            ignoresBatteryOptimizations = ignoresBatteryOptimizations,
            onOpenSettings = {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                ignoresBatteryOptimizations = isIgnoringBatteryOptimizations(context)
            }
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "省流量模式",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "安靜時暫停上傳；偵測到聲音時補送約 1 秒 pre-roll。預設關閉以維持最高喚醒率。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = lowBandwidthMode,
                        onCheckedChange = { enabled ->
                            lowBandwidthMode = enabled
                            scope.launch {
                                try {
                                    settingsStore.update { it.copy(lowBandwidthMode = enabled) }
                                    saveMessage = if (enabled) "省流量模式已儲存" else "全時串流模式已儲存"
                                } catch (e: Exception) {
                                    saveMessage = "儲存失敗: ${e.localizedMessage}"
                                    Timber.e(e, "Failed to save low bandwidth setting")
                                }
                            }
                        }
                    )
                }
                if (isServiceRunning) {
                    Text(
                        text = if (lowBandwidthMode != runtimeLowBandwidthMode)
                            "設定已改，請停止再啟動服務才會套用。"
                        else
                            "目前服務已套用這個模式。",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (lowBandwidthMode != runtimeLowBandwidthMode)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Server URL
        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = { Text("伺服器位址") },
            placeholder = { Text(ShrimpSettings.DEFAULT_SERVER_URL) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        // Session ID
        OutlinedTextField(
            value = sessionId,
            onValueChange = { sessionId = it },
            label = { Text("Session ID") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        // Auth token（伺服器 .env 的 SHRIMP_WS_TOKEN；留空 = 不驗證）
        OutlinedTextField(
            value = authToken,
            onValueChange = { authToken = it },
            label = { Text("連線 Token（選填）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        // Save button
        Button(
            onClick = {
                scope.launch {
                    try {
                        settingsStore.update {
                            it.copy(
                                serverUrl = serverUrl,
                                sessionId = sessionId,
                                authToken = authToken,
                                lowBandwidthMode = lowBandwidthMode
                            )
                        }
                        saveMessage = "設定已儲存"
                    } catch (e: Exception) {
                        saveMessage = "儲存失敗: ${e.localizedMessage}"
                        Timber.e(e, "Failed to save settings")
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("儲存設定")
        }

        if (saveMessage != null) {
            Text(saveMessage!!, color = Color(0xFF4CAF50))
            LaunchedEffect(saveMessage) {
                kotlinx.coroutines.delay(2000)
                saveMessage = null
            }
        }

        HorizontalDivider()

        // Start / Stop
        Button(
            onClick = {
                if (isServiceRunning) stopService() else startService()
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isServiceRunning)
                    MaterialTheme.colorScheme.error
                else
                    MaterialTheme.colorScheme.primary
            )
        ) {
            Text(if (isServiceRunning) "停止服務並關閉麥克風" else "啟動服務並開啟麥克風")
        }
    }
}

@Composable
private fun VoiceHeroCard(
    phase: VoiceSatelliteService.VoicePhase,
    isServiceRunning: Boolean,
    connectionState: ConnectionState
) {
    val accent = when {
        !isServiceRunning -> Color(0xFF78909C)
        connectionState != ConnectionState.CONNECTED -> Color(0xFFF9A825)
        phase == VoiceSatelliteService.VoicePhase.PLAYING ||
            phase == VoiceSatelliteService.VoicePhase.SPEAKING -> Color(0xFFFF7043)
        phase == VoiceSatelliteService.VoicePhase.THINKING ||
            phase == VoiceSatelliteService.VoicePhase.STT -> Color(0xFF1565C0)
        else -> Color(0xFF2E7D32)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.12f))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = phase.label,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = accent
            )
            Text(
                text = when {
                    !isServiceRunning -> "服務停止中，麥克風未開啟。"
                    connectionState != ConnectionState.CONNECTED -> "服務已啟動，正在等 WebSocket 重連。"
                    phase == VoiceSatelliteService.VoicePhase.IDLE -> "正在聽喚醒詞「蝦蝦」。"
                    phase == VoiceSatelliteService.VoicePhase.WAKE -> "已喚醒，請接著說話。"
                    phase == VoiceSatelliteService.VoicePhase.STT -> "正在把語音轉成文字。"
                    phase == VoiceSatelliteService.VoicePhase.THINKING -> "伺服器正在思考回覆。"
                    phase == VoiceSatelliteService.VoicePhase.SPEAKING -> "回覆已開始產生，正在合成語音。"
                    phase == VoiceSatelliteService.VoicePhase.PLAYING -> "正在播放蝦蝦的回覆，麥克風暫停避免自我喚醒。"
                    else -> "準備中。"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun BatteryOptimizationCard(
    ignoresBatteryOptimizations: Boolean,
    onOpenSettings: () -> Unit
) {
    if (ignoresBatteryOptimizations) return

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "背景執行建議",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Samsung 可能會限制背景麥克風與網路。若要長時間待命，建議到系統設定把蝦蝦加入電池最佳化例外。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(onClick = onOpenSettings) {
                Text("開啟電池最佳化設定")
            }
        }
    }
}

@Composable
private fun VoiceTimelineCard(
    logs: List<VoiceSatelliteService.VoiceLogEntry>,
    isServiceRunning: Boolean,
    connectionState: ConnectionState,
    phase: VoiceSatelliteService.VoicePhase
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "語音狀態",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = when {
                        !isServiceRunning -> "未啟動"
                        connectionState == ConnectionState.CONNECTED -> phase.label
                        connectionState == ConnectionState.CONNECTING -> "連線中"
                        else -> "重連中"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = when (connectionState) {
                        ConnectionState.CONNECTED -> Color(0xFF2E7D32)
                        ConnectionState.CONNECTING -> Color(0xFFF9A825)
                        ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            if (logs.isEmpty()) {
                Text(
                    text = if (isServiceRunning)
                        "尚未收到語音事件。說「蝦蝦」後，這裡會顯示辨識、思考與回覆紀錄。"
                    else
                        "啟動服務後，這裡會顯示最近的語音對話與系統狀態。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Column
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                logs.takeLast(12).forEach { entry ->
                    VoiceLogRow(entry)
                }
            }
        }
    }
}

@Composable
private fun VoiceLogRow(entry: VoiceSatelliteService.VoiceLogEntry) {
    val tagColor = when (entry.tag.uppercase(Locale.ROOT)) {
        "STT" -> Color(0xFF1565C0)
        "AI", "蝦蝦" -> Color(0xFF2E7D32)
        "ERR" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val label = when (entry.tag.uppercase(Locale.ROOT)) {
        "STT" -> "我"
        "AI", "蝦蝦" -> "蝦蝦"
        "ERR" -> "錯誤"
        else -> "系統"
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .padding(top = 6.dp)
                .background(tagColor, CircleShape)
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = tagColor,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = formatLogTime(entry.timestampMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = entry.msg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

private fun formatLogTime(timestampMs: Long): String {
    return SimpleDateFormat("HH:mm:ss", Locale.TAIWAN).format(Date(timestampMs))
}

private fun streamModeStatusText(
    configuredLowBandwidth: Boolean,
    runtimeLowBandwidth: Boolean,
    gateOpen: Boolean,
    isServiceRunning: Boolean
): String {
    if (isServiceRunning && configuredLowBandwidth != runtimeLowBandwidth) {
        return if (configuredLowBandwidth)
            "設定：省流量模式；目前仍是全時串流，重啟服務後套用。"
        else
            "設定：全時串流；目前仍是省流量模式，重啟服務後套用。"
    }
    return if (runtimeLowBandwidth) {
        if (gateOpen) "省流量模式：偵測到聲音，正在上傳。"
        else "省流量模式：安靜中，暫停上傳。"
    } else {
        "串流模式：全時上傳，喚醒率最高。"
    }
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}
