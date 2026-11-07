package com.botgram.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.botgram.app.data.Repo
import com.botgram.app.data.friendly
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotSettingsScreen(onBack: () -> Unit, onLoggedOut: () -> Unit) {
    val sess by Repo.session.collectAsState()
    val session = sess ?: return
    val bg by Repo.backgroundService.collectAsState()
    val auto by Repo.autoAnswer.collectAsState()
    val parse by Repo.parseMode.collectAsState()
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var me by remember { mutableStateOf<JSONObject?>(null) }
    var dialog by remember { mutableStateOf("") }
    var commands by remember { mutableStateOf("") }
    var confirmOut by remember { mutableStateOf(false) }
    var hook by remember { mutableStateOf("") }

    fun say(s: String) = scope.launch { snack.showSnackbar(s) }
    fun call(method: String, params: Map<String, Any?>, ok: String) = scope.launch {
        Repo.call(method, params).onSuccess { say(ok) }.onFailure { say(it.friendly()) }
    }

    LaunchedEffect(session.account.id) {
        Repo.call("getMe").onSuccess { me = it as? JSONObject }
        Repo.call("getMyCommands").onSuccess {
            val a = it as? JSONArray ?: return@onSuccess
            commands = (0 until a.length()).joinToString("\n") { i -> a.getJSONObject(i).let { c -> "${c.optString("command")} - ${c.optString("description")}" } }
        }
        Repo.call("getWebhookInfo").onSuccess { hook = (it as? JSONObject)?.optString("url").orEmpty() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text("Bot settings") },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            ListItem(
                headlineContent = { Text(session.account.name) },
                supportingContent = {
                    Text(
                        "@${session.account.username} · ID ${session.account.id}" +
                            me?.let {
                                "\nGroups: ${if (it.optBoolean("can_join_groups")) "can join" else "cannot join"}" +
                                    " · Privacy mode: ${if (it.optBoolean("can_read_all_group_messages")) "off (reads all)" else "on"}" +
                                    " · Inline: ${if (it.optBoolean("supports_inline_queries")) "yes" else "no"}"
                            }.orEmpty(),
                    )
                },
                leadingContent = { Avatar(session.account.name, session.account.id, 48.dp) },
            )
            HorizontalDivider()

            ListItem(
                headlineContent = { Text("Stay connected in background") },
                supportingContent = { Text("Keeps a foreground service running so messages arrive with the app closed.") },
                trailingContent = { Switch(checked = bg, onCheckedChange = { Repo.setBackground(it) }) },
            )
            ListItem(
                headlineContent = {
                    Text(
                        "Mode: " + when {
                            session.account.mtproto -> "MTProto (webhook kept)"
                            session.account.sendOnly -> "send-only (webhook kept)"
                            else -> "Bot API polling"
                        },
                    )
                },
                supportingContent = { Text("Tap to change how this bot receives messages.") },
                modifier = Modifier.clickable { dialog = "mode" },
            )
            ListItem(
                headlineContent = { Text("Auto-answer button presses") },
                supportingContent = { Text("Dismisses the loading spinner on users' inline buttons so they don't hang. Turn off if another process answers callbacks.") },
                trailingContent = { Switch(checked = auto, onCheckedChange = { Repo.setAutoAnswer(it) }) },
            )
            ListItem(
                headlineContent = { Text("Text formatting for sent messages") },
                supportingContent = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("" to "Plain", "HTML" to "HTML", "MarkdownV2" to "Markdown").forEach { (k, v) ->
                            FilterChip(selected = parse == k, onClick = { Repo.setParseMode(k) }, label = { Text(v) })
                        }
                    }
                },
            )
            HorizontalDivider()

            Text("Bot profile (as BotFather would)", Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            ListItem(headlineContent = { Text("Command list") }, supportingContent = { Text("setMyCommands") }, modifier = Modifier.clickable { dialog = "commands" })
            ListItem(headlineContent = { Text("Bot name") }, supportingContent = { Text("setMyName") }, modifier = Modifier.clickable { dialog = "name" })
            ListItem(headlineContent = { Text("Description") }, supportingContent = { Text("setMyDescription") }, modifier = Modifier.clickable { dialog = "desc" })
            ListItem(headlineContent = { Text("About text") }, supportingContent = { Text("setMyShortDescription") }, modifier = Modifier.clickable { dialog = "short" })
            HorizontalDivider()

            Text("Updates", Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            ListItem(
                headlineContent = { Text("Webhook") },
                supportingContent = { Text(if (hook.isEmpty()) "None — receiving via long polling" else hook) },
                modifier = Modifier.clickable {
                    if (hook.isNotEmpty()) scope.launch {
                        Repo.removeWebhook().onSuccess { hook = ""; say("Webhook removed") }.onFailure { say(it.friendly()) }
                    }
                },
                trailingContent = { if (hook.isNotEmpty()) Text("Tap to remove") },
            )
            ListItem(
                headlineContent = { Text("Drop pending updates") },
                supportingContent = { Text("Discards everything queued on Telegram's side") },
                modifier = Modifier.clickable {
                    scope.launch {
                        Repo.call("deleteWebhook", mapOf("drop_pending_updates" to true))
                            .onSuccess { say("Dropped") }.onFailure { say(it.friendly()) }
                    }
                },
            )
            HorizontalDivider()

            ListItem(
                headlineContent = { Text("Sign out of @${session.account.username}", color = MaterialTheme.colorScheme.error) },
                supportingContent = { Text("Removes the token and the local history from this device.") },
                modifier = Modifier.clickable { confirmOut = true },
            )
        }
    }

    if (confirmOut) {
        ConfirmDialog("Sign out?", "The token and all locally stored chats for this bot will be deleted from this device.", "Sign out",
            onDismiss = { confirmOut = false }) {
            Repo.logout(session.account.id)
            onLoggedOut()
        }
    }
    when (dialog) {
        "mode" -> AlertDialog(
            onDismissRequest = { dialog = "" },
            title = { Text("Receive mode") },
            text = {
                Column {
                    listOf(
                        Triple("poll", "Bot API polling", "Receives messages. Removes your webhook."),
                        Triple("mtproto", "MTProto", "Receives messages and keeps your webhook. Needs api_id/api_hash."),
                        Triple("sendonly", "Send-only", "Keeps your webhook; no incoming messages here."),
                    ).forEach { (k, t, d) ->
                        ListItem(
                            headlineContent = { Text(t) }, supportingContent = { Text(d) },
                            leadingContent = {
                                RadioButton(
                                    selected = when (k) {
                                        "mtproto" -> session.account.mtproto
                                        "sendonly" -> session.account.sendOnly
                                        else -> !session.account.mtproto && !session.account.sendOnly
                                    },
                                    onClick = null,
                                )
                            },
                            modifier = Modifier.clickable {
                                dialog = ""
                                scope.launch { Repo.setMode(k).onFailure { say(it.friendly()) } }
                            },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = "" }) { Text("Close") } },
        )
        "commands" -> TextInputDialog(
            "Commands", "one per line: command - description", commands, singleLine = false,
            onDismiss = { dialog = "" },
        ) { text ->
            val arr = JSONArray()
            text.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { l ->
                val p = l.removePrefix("/").split(" - ", limit = 2)
                arr.put(JSONObject().put("command", p[0].trim().lowercase()).put("description", p.getOrNull(1)?.trim().orEmpty().ifEmpty { p[0] }))
            }
            commands = text
            call("setMyCommands", mapOf("commands" to arr), "Commands updated")
        }
        "name" -> TextInputDialog("Bot name", "Name (max 64)", session.account.name, onDismiss = { dialog = "" }) {
            call("setMyName", mapOf("name" to it), "Name updated")
        }
        "desc" -> TextInputDialog("Description", "Shown in empty chat (max 512)", singleLine = false, onDismiss = { dialog = "" }) {
            call("setMyDescription", mapOf("description" to it), "Description updated")
        }
        "short" -> TextInputDialog("About", "Profile text (max 120)", singleLine = false, onDismiss = { dialog = "" }) {
            call("setMyShortDescription", mapOf("short_description" to it), "About updated")
        }
    }
}
