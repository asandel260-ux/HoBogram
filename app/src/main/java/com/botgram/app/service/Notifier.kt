package com.botgram.app.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.botgram.app.MainActivity
import com.botgram.app.R
import com.botgram.app.data.Account
import com.botgram.app.data.Chat
import com.botgram.app.data.Msg
import com.botgram.app.data.preview

object Notifier {
    const val CH_MSG = "messages"
    const val CH_SVC = "service"
    const val KEY_REPLY = "reply"

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_MSG, "Messages", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_SVC, "Background connection", NotificationManager.IMPORTANCE_MIN))
    }

    private fun canPost(ctx: Context) =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun cancel(ctx: Context, chatId: Long) {
        NotificationManagerCompat.from(ctx).cancel(chatId.hashCode())
    }

    fun message(ctx: Context, acc: Account, chat: Chat, m: Msg) {
        if (!canPost(ctx)) return
        val open = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("chat_id", chat.id)
            putExtra("acc_id", acc.id)
            data = Uri.parse("botgram://chat/${acc.id}/${chat.id}")
        }
        val openPi = PendingIntent.getActivity(
            ctx, chat.id.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val replyIntent = Intent(ctx, ReplyReceiver::class.java).apply {
            putExtra("chat_id", chat.id)
            putExtra("acc_id", acc.id)
            data = Uri.parse("botgram://reply/${acc.id}/${chat.id}")
        }
        val replyPi = PendingIntent.getBroadcast(
            ctx, chat.id.hashCode(), replyIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val remote = RemoteInput.Builder(KEY_REPLY).setLabel("Reply").build()
        val action = NotificationCompat.Action.Builder(R.drawable.ic_notification, "Reply", replyPi)
            .addRemoteInput(remote).build()

        val body = if (chat.isPrivate) m.preview() else "${m.fromName}: ${m.preview()}"
        val n = NotificationCompat.Builder(ctx, CH_MSG)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(chat.title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openPi)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .addAction(action)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(chat.id.hashCode(), n)
        } catch (e: SecurityException) {
            // permission revoked meanwhile
        }
    }
}
