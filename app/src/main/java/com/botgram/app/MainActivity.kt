package com.botgram.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.botgram.app.data.Repo
import com.botgram.app.service.PollingService
import com.botgram.app.ui.BotGramTheme
import com.botgram.app.ui.BotSettingsScreen
import com.botgram.app.ui.BroadcastScreen
import com.botgram.app.ui.ChatInfoScreen
import com.botgram.app.ui.ChatListScreen
import com.botgram.app.ui.ChatScreen
import com.botgram.app.ui.ConsoleScreen
import com.botgram.app.ui.LoginScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent { BotGramTheme { AppRoot() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        if (Repo.session.value != null && Repo.backgroundService.value) PollingService.start(this)
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        val chat = i.getLongExtra("chat_id", Long.MIN_VALUE)
        if (chat != Long.MIN_VALUE) Repo.openRequest.value = i.getLongExtra("acc_id", 0) to chat
    }
}

@Composable
private fun AppRoot() {
    val nav = rememberNavController()
    val session by Repo.session.collectAsState()
    val request by Repo.openRequest.collectAsState()
    val ctx = LocalContext.current

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(session?.account?.id) {
        if (session != null) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (Repo.backgroundService.value) PollingService.start(ctx)
        }
    }

    LaunchedEffect(request) {
        val r = request ?: return@LaunchedEffect
        if (Repo.session.value?.account?.id != r.first) {
            Repo.accounts.list().firstOrNull { it.id == r.first }?.let { Repo.activate(it) }
        }
        if (Repo.session.value != null) nav.navigate("chat/${r.second}") { launchSingleTop = true }
        Repo.openRequest.value = null
    }

    fun goHome() = nav.navigate("chats") { popUpTo(0) }
    fun goLogin() = nav.navigate("login") { popUpTo(0) }

    NavHost(navController = nav, startDestination = if (Repo.session.value != null) "chats" else "login") {
        composable("login") {
            LoginScreen(
                onLoggedIn = { goHome() },
                onBack = if (session != null) ({ nav.popBackStack() }) else null,
            )
        }
        composable("chats") {
            ChatListScreen(
                onChat = { nav.navigate("chat/$it") },
                onAddBot = { nav.navigate("login") },
                onSettings = { nav.navigate("settings") },
                onBroadcast = { nav.navigate("broadcast") },
                onConsole = { nav.navigate("console") },
            )
        }
        composable("chat/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
            val id = it.arguments!!.getLong("id")
            ChatScreen(chatId = id, onBack = { nav.popBackStack() }, onInfo = { nav.navigate("info/$id") })
        }
        composable("info/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
            val id = it.arguments!!.getLong("id")
            ChatInfoScreen(
                chatId = id,
                onBack = { nav.popBackStack() },
                onLeft = { goHome() },
                onOpenChat = { other -> nav.navigate("chat/$other") },
            )
        }
        composable("settings") {
            BotSettingsScreen(
                onBack = { nav.popBackStack() },
                onLoggedOut = { if (Repo.session.value == null) goLogin() else goHome() },
            )
        }
        composable("broadcast") { BroadcastScreen(onBack = { nav.popBackStack() }) }
        composable("console") { ConsoleScreen(onBack = { nav.popBackStack() }) }
    }
}
