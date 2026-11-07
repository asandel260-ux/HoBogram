package com.botgram.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.botgram.app.data.Account
import com.botgram.app.data.Repo
import com.botgram.app.data.friendly
import kotlinx.coroutines.launch

private val TOKEN_REGEX = Regex("^\\d{5,}:[A-Za-z0-9_-]{30,}$")

@Composable
fun LoginScreen(onLoggedIn: () -> Unit, onBack: (() -> Unit)?) {
    var token by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingHook by remember { mutableStateOf<Account?>(null) }
    var apiId by remember { mutableStateOf(Repo.accounts.apiId.takeIf { it != 0 }?.toString().orEmpty()) }
    var apiHash by remember { mutableStateOf(Repo.accounts.apiHash) }

    fun saveApi(): Boolean {
        val id = apiId.trim().toIntOrNull()
        val h = apiHash.trim()
        if (id == null || id <= 0 || h.length < 20) return false
        Repo.accounts.apiId = id
        Repo.accounts.apiHash = h
        return true
    }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    fun finish(acc: Account, removeHook: Boolean, mode: String = "poll") {
        busy = true
        scope.launch {
            Repo.commitLogin(acc, removeHook, mode)
                .onSuccess { onLoggedIn() }
                .onFailure { error = it.friendly() }
            busy = false
        }
    }

    fun connect() {
        val t = token.trim()
        if (!TOKEN_REGEX.matches(t)) {
            error = "That doesn't look like a bot token. It should look like 123456789:AAH…"
            return
        }
        error = null
        busy = true
        scope.launch {
            Repo.probe(t)
                .onSuccess { (acc, hasHook) ->
                    if (hasHook) {
                        pendingHook = acc
                        busy = false
                    } else {
                        busy = false
                        finish(acc, false)
                    }
                }
                .onFailure {
                    error = it.friendly()
                    busy = false
                }
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            } else {
                Spacer(Modifier.height(32.dp))
            }
            Icon(Icons.Default.SmartToy, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
            Text("BotGram", style = MaterialTheme.typography.headlineLarge)
            Text(
                "A Telegram client for bots. Sign in with the token from @BotFather and chat in private " +
                    "chats, groups, supergroups and channels as your bot.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it; error = null },
                label = { Text("Bot token") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                isError = error != null,
                visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                trailingIcon = {
                    Row {
                        IconButton(onClick = { clipboard.getText()?.let { token = it.text.trim() } }) {
                            Icon(Icons.Default.ContentPaste, "Paste")
                        }
                        IconButton(onClick = { show = !show }) {
                            Icon(if (show) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Show or hide")
                        }
                    }
                },
            )
            Text("MTProto settings (optional)", style = MaterialTheme.typography.titleSmall)
            Text(
                "Needed only to receive messages while keeping your webhook. Create an app at my.telegram.org → API development tools, then enter its api_id and api_hash.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = apiId, onValueChange = { apiId = it.filter(Char::isDigit) }, label = { Text("api_id") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            OutlinedTextField(
                value = apiHash, onValueChange = { apiHash = it.trim() }, label = { Text("api_hash") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Button(onClick = { connect() }, enabled = !busy && token.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Connect")
            }
            Text(
                "Your token is stored encrypted on this device and only ever sent to api.telegram.org.\n\n" +
                    "How to get one: open @BotFather in Telegram → /newbot (or /mybots → your bot → API Token).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    pendingHook?.let { acc ->
        AlertDialog(
            onDismissRequest = { pendingHook = null },
            title = { Text("Webhook is active") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "@${acc.username} has a webhook. The Bot API lets only one consumer receive updates, " +
                            "so choose how BotGram should work:",
                    )
                    TextButton(onClick = { pendingHook = null; finish(acc, true, "poll") }) {
                        Text("Remove webhook and receive here")
                    }
                    TextButton(onClick = {
                        if (saveApi()) {
                            pendingHook = null; finish(acc, false, "mtproto")
                        } else {
                            pendingHook = null
                            error = "Enter a valid api_id and api_hash below, then connect again."
                        }
                    }) { Text("Keep webhook, receive via MTProto") }
                    TextButton(onClick = { pendingHook = null; finish(acc, false, "sendonly") }) {
                        Text("Keep webhook, send-only")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pendingHook = null }) { Text("Cancel") } },
        )
    }
}
