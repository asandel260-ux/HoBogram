package com.botgram.app

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.botgram.app.data.Repo
import com.botgram.app.service.Notifier

class BotGramApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        Repo.init(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                Repo.foreground = true
            }

            override fun onStop(owner: LifecycleOwner) {
                Repo.foreground = false
            }
        })
    }
}
