package com.botgram.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.botgram.app.data.Repo
import kotlinx.coroutines.launch

/** Handles the inline "Reply" action of message notifications. */
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notifier.KEY_REPLY)?.toString()
        val chatId = intent.getLongExtra("chat_id", 0)
        val accId = intent.getLongExtra("acc_id", 0)
        if (text.isNullOrBlank() || chatId == 0L) return
        val pending = goAsync()
        Repo.scope.launch {
            try {
                Repo.replyFromNotification(accId, chatId, text)
            } finally {
                Notifier.cancel(context, chatId)
                pending.finish()
            }
        }
    }
}
