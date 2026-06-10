package com.shrimp.voice.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import androidx.core.app.NotificationCompat
import com.shrimp.voice.MainActivity
import com.shrimp.voice.services.VoiceSatelliteService

const val VOICE_SATELLITE_SERVICE_CHANNEL_ID = "ShrimpVoiceSatelliteService"

fun createVoiceSatelliteServiceNotificationChannel(context: Context) {
    val channelName = context.getString(com.shrimp.voice.R.string.channel_name)
    val chan = NotificationChannel(
        VOICE_SATELLITE_SERVICE_CHANNEL_ID,
        channelName,
        NotificationManager.IMPORTANCE_LOW
    )
    chan.lightColor = Color.BLUE
    chan.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    manager.createNotificationChannel(chan)
}

fun createVoiceSatelliteServiceNotification(
    context: Context,
    content: String = context.getString(com.shrimp.voice.R.string.service_notification_content)
): Notification {
    val pendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        },
        PendingIntent.FLAG_IMMUTABLE
    )
    val stopIntent = PendingIntent.getService(
        context,
        1,
        Intent(context, VoiceSatelliteService::class.java).apply {
            action = VoiceSatelliteService.ACTION_STOP_MIC
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    return NotificationCompat.Builder(context, VOICE_SATELLITE_SERVICE_CHANNEL_ID)
        .setOngoing(true)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle("蝦蝦 Voice")
        .setContentText(content)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setCategory(Notification.CATEGORY_SERVICE)
        .setContentIntent(pendingIntent)
        .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止麥克風", stopIntent)
        .build()
}
