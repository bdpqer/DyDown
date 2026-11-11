package com.dydown.app.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.dydown.app.MainActivity
import com.dydown.app.R
import com.dydown.app.service.LiveRecordService

object Notifier {

    const val CH_DOWNLOAD = "download"
    const val CH_LIVE = "live"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CH_DOWNLOAD, "下载任务", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_LIVE, "直播录制", NotificationManager.IMPORTANCE_LOW)
        )
    }

    fun build(
        context: Context,
        channel: String,
        title: String,
        text: String,
        progress: Int = -1,
        ongoing: Boolean = true,
        actions: List<NotificationCompat.Action> = emptyList()
    ): android.app.Notification {
        val pi = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        actions.forEach { b.addAction(it) }
        if (progress >= 0) b.setProgress(100, progress.coerceIn(0, 100), false)
        return b.build()
    }

    fun stopAction(context: Context, action: String): PendingIntent =
        PendingIntent.getService(
            context, 1,
            Intent(context, LiveRecordService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
