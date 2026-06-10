package com.shrimp.voice.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shrimp.voice.network.ConnectionState
import com.shrimp.voice.services.VoiceSatelliteService
import com.shrimp.voice.settings.ShrimpSettings
import com.shrimp.voice.settings.shrimpSettingsStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import timber.log.Timber

// ===== 蝦蝦配色 =====
private val BgDeep = Color(0xFF0E0F13)
private val Surface1 = Color(0xFF181A21)
private val Surface2 = Color(0xFF20232C)
private val Coral = Color(0xFFFF7A5C)
private val CoralDim = Color(0xFF8C4A3C)
private val Mint = Color(0xFF4ADE80)
private val Amber = Color(0xFFFBBF24)
private val Sky = Color(0xFF60A5FA)
private val Violet = Color(0xFFA78BFA)
private val Ink = Color(0xFFEDEAE6)
private val InkDim = Color(0xFF8A8F98)

private val ShrimpColors = darkColorScheme(
    primary = Coral,
    onPrimary = Color(0xFF2A120C),
    background = BgDeep,
    onBackground = Ink,
    surface = Surface1,
    onSurface = Ink,
    surfaceVariant = Surface2,
    onSurfaceVariant = InkDim,
    error = Color(0xFFFF6B6B)
)

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
    var showSettings by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val settings = settingsStore.get()
        serverUrl = settings.serverUrl
        sessionId = settings.sessionId
        authToken = settings.authToken
        lowBandwidthMode = settings.lowBandwidthMode
    }

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

    MaterialTheme(colorScheme = ShrimpColors) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color(0xFF151018),
                        0.35f to BgDeep,
                        1f to BgDeep
                    )
                )
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── 頂列：標題 + 齒輪 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("蝦蝦", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink)
                Text(
                    "  VOICE",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Coral,
                    letterSpacing = 3.sp
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { showSettings = !showSettings }) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = "設定",
                        tint = if (showSettings) Coral else InkDim
                    )
                }
            }

            // ── 狀態球 ──
            StatusOrb(
                phase = voicePhase,
                connectionState = connectionState,
                isServiceRunning = isServiceRunning
            )

            // ── 狀態膠囊列 ──
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 14.dp)
            ) {
                val (connText, connColor) = when {
                    !isServiceRunning -> "離線" to InkDim
                    connectionState == ConnectionState.CONNECTED -> "已連線" to Mint
                    connectionState == ConnectionState.CONNECTING -> "連線中" to Amber
                    else -> "重連中" to Amber
                }
                StatusPill(connText, connColor)
                if (isServiceRunning && runtimeLowBandwidthMode) {
                    StatusPill(if (gateOpen) "省流量 · 上傳中" else "省流量 · 靜音", if (gateOpen) Coral else InkDim)
                }
                if (isServiceRunning) {
                    StatusPill("↑ ${formatKb(sentAudioKb)}", InkDim)
                }
            }

            if (lastError != null) {
                Text(
                    "⚠ $lastError",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }

            // ── 設定面板（齒輪展開）──
            AnimatedVisibility(
                visible = showSettings,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                SettingsPanel(
                    serverUrl = serverUrl, onServerUrl = { serverUrl = it },
                    sessionId = sessionId, onSessionId = { sessionId = it },
                    authToken = authToken, onAuthToken = { authToken = it },
                    lowBandwidthMode = lowBandwidthMode,
                    onLowBandwidth = { enabled ->
                        lowBandwidthMode = enabled
                        scope.launch {
                            try {
                                settingsStore.update { it.copy(lowBandwidthMode = enabled) }
                                saveMessage = if (enabled) "省流量模式已儲存" else "全時串流已儲存"
                            } catch (e: Exception) {
                                saveMessage = "儲存失敗: ${e.localizedMessage}"
                                Timber.e(e, "Failed to save low bandwidth setting")
                            }
                        }
                    },
                    pendingModeChange = isServiceRunning && lowBandwidthMode != runtimeLowBandwidthMode,
                    onSave = {
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
                    saveMessage = saveMessage,
                    showBatteryHint = !ignoresBatteryOptimizations,
                    onOpenBatterySettings = {
                        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        ignoresBatteryOptimizations = isIgnoringBatteryOptimizations(context)
                    },
                    debugLine = if (isServiceRunning)
                        "RMS $audioRms（avg $avgRms / peak $maxRms）· gate $gateOpenPercent% · 省略 $suppressedFrames · 丟棄 $droppedFrames"
                    else null
                )
            }

            if (saveMessage != null && !showSettings) {
                Text(saveMessage!!, color = Mint, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
            LaunchedEffect(saveMessage) {
                if (saveMessage != null) {
                    kotlinx.coroutines.delay(2500)
                    saveMessage = null
                }
            }

            // ── 對話區 ──
            ConversationCard(
                logs = voiceLogs,
                isServiceRunning = isServiceRunning,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
            )

            Spacer(Modifier.height(16.dp))

            // ── 主按鈕 ──
            Button(
                onClick = { if (isServiceRunning) stopService() else startService() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isServiceRunning) Surface2 else Coral,
                    contentColor = if (isServiceRunning) Ink else Color(0xFF2A120C)
                )
            ) {
                Text(
                    if (isServiceRunning) "停止" else "開始聆聽",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}

// ===== 狀態球 =====
@Composable
private fun StatusOrb(
    phase: VoiceSatelliteService.VoicePhase,
    connectionState: ConnectionState,
    isServiceRunning: Boolean
) {
    val accent: Color
    val label: String
    val hint: String
    when {
        !isServiceRunning -> { accent = InkDim; label = "休眠中"; hint = "按下方按鈕開始" }
        connectionState != ConnectionState.CONNECTED -> { accent = Amber; label = "連線中"; hint = "正在找伺服器…" }
        else -> when (phase) {
            VoiceSatelliteService.VoicePhase.WAKE -> { accent = Coral; label = "在聽！"; hint = "請說…" }
            VoiceSatelliteService.VoicePhase.STT -> { accent = Sky; label = "辨識中"; hint = "正在聽懂你說的話" }
            VoiceSatelliteService.VoicePhase.THINKING -> { accent = Violet; label = "思考中"; hint = "蝦蝦正在想" }
            VoiceSatelliteService.VoicePhase.SPEAKING,
            VoiceSatelliteService.VoicePhase.PLAYING -> { accent = Coral; label = "回答中"; hint = "麥克風暫停防回音" }
            else -> { accent = Mint; label = "待命中"; hint = "喊「蝦蝦」叫我" }
        }
    }

    val active = isServiceRunning && connectionState == ConnectionState.CONNECTED
    val busy = active && phase != VoiceSatelliteService.VoicePhase.IDLE
    val transition = rememberInfiniteTransition(label = "orb")
    val pulse by transition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            tween(durationMillis = if (busy) 550 else 1800),
            RepeatMode.Reverse
        ),
        label = "pulse"
    )
    val ringAlpha by transition.animateFloat(
        initialValue = 0.12f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(
            tween(durationMillis = if (busy) 550 else 1800),
            RepeatMode.Reverse
        ),
        label = "ring"
    )
    val scale = if (active) pulse else 1f

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 18.dp)) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(190.dp)) {
            // 外圈光暈
            Box(
                Modifier
                    .size(190.dp)
                    .scale(scale)
                    .border(2.dp, accent.copy(alpha = if (active) ringAlpha else 0.1f), CircleShape)
            )
            Box(
                Modifier
                    .size(160.dp)
                    .scale(scale)
                    .background(
                        Brush.radialGradient(
                            0f to accent.copy(alpha = 0.28f),
                            0.75f to accent.copy(alpha = 0.08f),
                            1f to Color.Transparent
                        ),
                        CircleShape
                    )
            )
            // 本體
            Box(
                Modifier
                    .size(124.dp)
                    .scale(scale)
                    .background(Surface1, CircleShape)
                    .border(1.5.dp, accent.copy(alpha = 0.55f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("🦐", fontSize = 52.sp)
            }
        }
        Text(label, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = accent, modifier = Modifier.padding(top = 4.dp))
        Text(hint, fontSize = 13.sp, color = InkDim, modifier = Modifier.padding(top = 2.dp))
    }
}

// ===== 狀態膠囊 =====
@Composable
private fun StatusPill(text: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(Surface1, RoundedCornerShape(50))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 5.dp)
    ) {
        Box(
            Modifier
                .size(7.dp)
                .background(color, CircleShape)
        )
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 12.sp, color = Ink.copy(alpha = 0.85f))
    }
}

// ===== 對話區（聊天泡泡）=====
@Composable
private fun ConversationCard(
    logs: List<VoiceSatelliteService.VoiceLogEntry>,
    isServiceRunning: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(Surface1.copy(alpha = 0.6f), RoundedCornerShape(24.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (logs.isEmpty()) {
            Text(
                if (isServiceRunning) "對話會出現在這裡。試試喊「蝦蝦」！"
                else "還沒有對話。開始聆聽後喊「蝦蝦」。",
                fontSize = 13.sp,
                color = InkDim,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 14.dp)
            )
            return@Column
        }
        logs.takeLast(14).forEach { entry -> ChatRow(entry) }
    }
}

@Composable
private fun ChatRow(entry: VoiceSatelliteService.VoiceLogEntry) {
    when (entry.tag.uppercase(Locale.ROOT)) {
        "STT" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Bubble(
                text = entry.msg,
                time = formatLogTime(entry.timestampMs),
                bg = CoralDim.copy(alpha = 0.45f),
                border = Coral.copy(alpha = 0.4f),
                shape = RoundedCornerShape(18.dp, 4.dp, 18.dp, 18.dp)
            )
        }
        "AI", "蝦蝦" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            Column {
                Text("🦐 蝦蝦", fontSize = 11.sp, color = Coral, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
                Bubble(
                    text = entry.msg,
                    time = formatLogTime(entry.timestampMs),
                    bg = Surface2,
                    border = Color.White.copy(alpha = 0.06f),
                    shape = RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp)
                )
            }
        }
        "ERR" -> Text(
            "⚠ ${entry.msg}",
            fontSize = 11.sp,
            color = Color(0xFFFF6B6B),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        else -> Text(
            entry.msg,
            fontSize = 11.sp,
            color = InkDim,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun Bubble(
    text: String,
    time: String,
    bg: Color,
    border: Color,
    shape: RoundedCornerShape
) {
    Column(
        modifier = Modifier
            .widthIn(max = 290.dp)
            .background(bg, shape)
            .border(1.dp, border, shape)
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) {
        Text(text, fontSize = 15.sp, color = Ink, lineHeight = 21.sp)
        Text(time, fontSize = 10.sp, color = InkDim, modifier = Modifier.padding(top = 3.dp))
    }
}

// ===== 設定面板 =====
@Composable
private fun SettingsPanel(
    serverUrl: String, onServerUrl: (String) -> Unit,
    sessionId: String, onSessionId: (String) -> Unit,
    authToken: String, onAuthToken: (String) -> Unit,
    lowBandwidthMode: Boolean, onLowBandwidth: (Boolean) -> Unit,
    pendingModeChange: Boolean,
    onSave: () -> Unit,
    saveMessage: String?,
    showBatteryHint: Boolean,
    onOpenBatterySettings: () -> Unit,
    debugLine: String?
) {
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Coral,
        unfocusedBorderColor = Color.White.copy(alpha = 0.12f),
        focusedLabelColor = Coral,
        unfocusedLabelColor = InkDim,
        cursorColor = Coral,
        focusedTextColor = Ink,
        unfocusedTextColor = Ink
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp)
            .background(Surface1, RoundedCornerShape(24.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("連線設定", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        OutlinedTextField(
            value = serverUrl, onValueChange = onServerUrl,
            label = { Text("伺服器位址") },
            placeholder = { Text(ShrimpSettings.DEFAULT_SERVER_URL) },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            shape = RoundedCornerShape(14.dp), colors = fieldColors
        )
        OutlinedTextField(
            value = sessionId, onValueChange = onSessionId,
            label = { Text("Session ID（選填）") },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            shape = RoundedCornerShape(14.dp), colors = fieldColors
        )
        OutlinedTextField(
            value = authToken, onValueChange = onAuthToken,
            label = { Text("連線 Token（選填）") },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            shape = RoundedCornerShape(14.dp), colors = fieldColors
        )

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("省流量模式", fontSize = 14.sp, color = Ink)
                Text(
                    if (pendingModeChange) "已更改，重啟服務後套用" else "安靜時暫停上傳，喚醒率略降",
                    fontSize = 11.sp,
                    color = if (pendingModeChange) Amber else InkDim
                )
            }
            Switch(
                checked = lowBandwidthMode,
                onCheckedChange = onLowBandwidth,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Coral,
                    uncheckedThumbColor = InkDim,
                    uncheckedTrackColor = Surface2
                )
            )
        }

        Button(
            onClick = onSave,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Surface2, contentColor = Ink)
        ) { Text("儲存設定") }

        if (saveMessage != null) {
            Text(saveMessage, color = Mint, fontSize = 12.sp)
        }

        if (showBatteryHint) {
            HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
            Text(
                "想長時間待命？建議把蝦蝦加入電池最佳化例外（三星很愛殺背景）。",
                fontSize = 12.sp, color = InkDim
            )
            OutlinedButton(
                onClick = onOpenBatterySettings,
                shape = RoundedCornerShape(14.dp),
                border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                    brush = Brush.linearGradient(listOf(Coral.copy(alpha = 0.5f), Coral.copy(alpha = 0.5f)))
                )
            ) { Text("開啟電池設定", color = Coral) }
        }

        if (debugLine != null) {
            HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
            Text(debugLine, fontSize = 11.sp, color = InkDim, lineHeight = 16.sp)
        }
    }
}

private fun formatLogTime(timestampMs: Long): String {
    return SimpleDateFormat("HH:mm:ss", Locale.TAIWAN).format(Date(timestampMs))
}

private fun formatKb(kb: Long): String {
    return if (kb >= 1024) "%.1f MB".format(kb / 1024.0) else "$kb KB"
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}
