package com.botgram.app.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.botgram.app.MainActivity
import com.botgram.app.R
import com.botgram.app.data.Repo

/**
 * Keeps the process alive so the long-poll loop in [Repo] keeps receiving updates while the app
 * is in the background. The polling itself lives in Repo.
 */
class PollingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val s = Repo.session.value
        if (s == null || !Repo.backgroundService.value) {
            stopSelf()
            return START_NOT_STICKY
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, Notifier.CH_SVC)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("BotGram is connected")
            .setContentText("@${s.account.username} is listening for messages")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
        return START_STICKY
    }

    companion object {
        fun start(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, PollingService::class.java))
            } catch (e: Exception) {
                // not allowed from background on some devices; polling continues while the app is open
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, PollingService::class.java))
        }
    }
}
