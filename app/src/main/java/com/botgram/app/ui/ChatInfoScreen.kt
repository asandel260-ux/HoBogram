package com.botgram.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.botgram.app.data.Chat
import com.botgram.app.data.Repo
import com.botgram.app.data.friendly
import com.botgram.app.data.objects
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private val USER_ACTIONS = listOf(
    "ban" to "Ban",
    "ban_revoke" to "Ban & delete messages",
    "unban" to "Unban / let rejoin",
    "mute_1h" to "Mute for 1 hour",
    "mute_1d" to "Mute for 1 day",
    "mute_forever" to "Mute forever",
    "unmute" to "Unmute / lift restrictions",
    "promote" to "Promote to admin",
    "demote" to "Demote admin",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatInfoScreen(chatId: Long, onBack: () -> Unit, onLeft: () -> Unit, onOpenChat: (Long) -> Unit) {
    val sess by Repo.session.collectAsState()
    val session = sess ?: return
    val store = session.store
    val chats by store.chats.collectAsState()
    val joins by store.joinRequests.collectAsState()
    val messages by store.messages.collectAsState()
    val chat = chats.firstOrNull { it.id == chatId } ?: Chat(chatId, "private", chatId.toString())
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current

    var info by remember { mutableStateOf<JSONObject?>(null) }
    var count by remember { mutableStateOf<Int?>(null) }
    var admins by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var dialog by remember { mutableStateOf("") }
    var confirmLeave by remember { mutableStateOf(false) }
    var userMenu by remember { mutableStateOf<Long?>(null) }
    var inviteLink by remember { mutableStateOf<String?>(null) }

    fun say(s: String) = scope.launch { snack.showSnackbar(s) }
    fun report(r: Result<*>, ok: String? = null) {
        r.onSuccess { if (ok != null) say(ok) }.onFailure { say(it.friendly()) }
    }

    LaunchedEffect(chatId) {
        Repo.call("getChat", mapOf("chat_id" to chatId)).onSuccess { info = it as? JSONObject }
        if (!chat.isPrivate) {
            Repo.call("getChatMemberCount", mapOf("chat_id" to chatId)).onSuccess { count = (it as? Int) }
            if (!chat.isChannel || true) {
                Repo.call("getChatAdministrators", mapOf("chat_id" to chatId))
                    .onSuccess { admins = (it as? JSONArray)?.objects().orEmpty() }
            }
        }
    }

    val seenUsers = remember(messages[chatId]) { store.usersIn(chatId) }
    val pending = joins.filter { it.chatId == chatId }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text("Chat info") },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(chat.title, chat.id, 80.dp)
                Text(chat.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 10.dp))
                Text(chat.type + (chat.username?.let { " · @$it" } ?: ""), style = MaterialTheme.typography.bodyMedium)
                count?.let { Text("$it members", style = MaterialTheme.typography.bodySmall) }
                info?.optString("description")?.takeIf { it.isNotBlank() }?.let {
                    Text(it, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
                }
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("ID: ${chat.id}", style = MaterialTheme.typography.labelMedium)
                    TextButton(onClick = { clipboard.setText(AnnotatedString(chat.id.toString())); say("Copied") }) { Text("Copy") }
                }
            }
            HorizontalDivider()

            if (!chat.isPrivate) {
                Section("Chat settings")
                ListItem(headlineContent = { Text("Change title") }, modifier = Modifier.clickable { dialog = "title" })
                ListItem(headlineContent = { Text("Change description") }, modifier = Modifier.clickable { dialog = "desc" })
                ListItem(headlineContent = { Text("Create invite link") }, modifier = Modifier.clickable {
                    scope.launch {
                        Repo.call("createChatInviteLink", mapOf("chat_id" to chatId))
                            .onSuccess { inviteLink = (it as? JSONObject)?.optString("invite_link") }
                            .onFailure { say(it.friendly()) }
                    }
                })
                ListItem(headlineContent = { Text("Export primary invite link") }, modifier = Modifier.clickable {
                    scope.launch {
                        Repo.call("exportChatInviteLink", mapOf("chat_id" to chatId))
                            .onSuccess { inviteLink = it as? String }
                            .onFailure { say(it.friendly()) }
                    }
                })
                ListItem(headlineContent = { Text("Unpin all messages") }, modifier = Modifier.clickable {
                    scope.launch { report(Repo.call("unpinAllChatMessages", mapOf("chat_id" to chatId)), "Unpinned") }
                })
                ListItem(headlineContent = { Text("Set default member permissions") }, modifier = Modifier.clickable { dialog = "perms" })
                HorizontalDivider()
            }

            if (!chat.isPrivate && !chat.isChannel) {
                Section("Members (${seenUsers.size} seen)")
                if (seenUsers.isEmpty()) {
                    Text(
                        "Bots can't list members. Users appear here after they send a message the bot can see.",
                        Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall,
                    )
                }
                seenUsers.forEach { (uid, name) ->
                    Box2 {
                        ListItem(
                            headlineContent = { Text(name.ifEmpty { uid.toString() }) },
                            supportingContent = { Text("ID $uid") },
                            leadingContent = { Avatar(name, uid, 36.dp) },
                            modifier = Modifier.clickable { userMenu = uid },
                        )
                        DropdownMenu(expanded = userMenu == uid, onDismissRequest = { userMenu = null }) {
                            USER_ACTIONS.forEach { (k, v) ->
                                DropdownMenuItem(text = { Text(v) }, onClick = {
                                    userMenu = null
                                    scope.launch { report(Repo.userAction(chatId, uid, k), "$v: done") }
                                })
                            }
                        }
                    }
                }
                OutlinedButton(onClick = { dialog = "userid" }, modifier = Modifier.padding(16.dp)) { Text("Act on a user by ID…") }
                HorizontalDivider()
            }

            if (pending.isNotEmpty()) {
                Section("Join requests (${pending.size})")
                pending.forEach { r ->
                    ListItem(
                        headlineContent = { Text(r.name) },
                        supportingContent = { Text(listOfNotNull(r.username?.let { "@$it" }, r.bio).joinToString(" · ")) },
                        trailingContent = {
                            Row {
                                TextButton(onClick = {
                                    scope.launch {
                                        Repo.call("declineChatJoinRequest", mapOf("chat_id" to chatId, "user_id" to r.userId))
                                            .onSuccess { store.removeJoin(chatId, r.userId) }.onFailure { say(it.friendly()) }
                                    }
                                }) { Text("Decline") }
                                TextButton(onClick = {
                                    scope.launch {
                                        Repo.call("approveChatJoinRequest", mapOf("chat_id" to chatId, "user_id" to r.userId))
                                            .onSuccess { store.removeJoin(chatId, r.userId) }.onFailure { say(it.friendly()) }
                                    }
                                }) { Text("Approve") }
                            }
                        },
                    )
                }
                HorizontalDivider()
            }

            if (admins.isNotEmpty()) {
                Section("Administrators")
                admins.forEach { a ->
                    val u = a.optJSONObject("user")
                    ListItem(
                        headlineContent = { Text(com.botgram.app.data.userName(u)) },
                        supportingContent = {
                            Text(
                                (if (a.optString("status") == "creator") "Owner" else "Admin") +
                                    (if (u?.optBoolean("is_bot") == true) " · bot" else ""),
                            )
                        },
                        leadingContent = { Avatar(com.botgram.app.data.userName(u), u?.optLong("id") ?: 0, 36.dp) },
                    )
                }
                HorizontalDivider()
            }

            if (chat.isPrivate) {
                Section("User")
                ListItem(headlineContent = { Text("Get profile photos count") }, modifier = Modifier.clickable {
                    scope.launch {
                        Repo.call("getUserProfilePhotos", mapOf("user_id" to chatId, "limit" to 1)).onSuccess {
                            say("Profile photos: ${(it as? JSONObject)?.optInt("total_count") ?: 0}")
                        }.onFailure { say(it.friendly()) }
                    }
                })
                HorizontalDivider()
            }

            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!chat.isPrivate) {
                    Button(onClick = { confirmLeave = true }, modifier = Modifier.fillMaxWidth()) { Text("Leave chat") }
                }
                OutlinedButton(onClick = { store.removeChat(chatId); onLeft() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Remove from list & clear local history")
                }
            }
        }
    }

    inviteLink?.let { link ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { inviteLink = null },
            title = { Text("Invite link") },
            text = { Text(link) },
            confirmButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(link)); inviteLink = null }) { Text("Copy") } },
            dismissButton = { TextButton(onClick = { inviteLink = null }) { Text("Close") } },
        )
    }
    if (confirmLeave) {
        ConfirmDialog("Leave ${chat.title}?", "The bot will leave and stop receiving messages from this chat.", "Leave",
            onDismiss = { confirmLeave = false }) {
            scope.launch {
                Repo.call("leaveChat", mapOf("chat_id" to chatId)).onSuccess {
                    store.markLeft(chatId, true); onLeft()
                }.onFailure { say(it.friendly()) }
            }
        }
    }
    when (dialog) {
        "title" -> TextInputDialog("Change title", "New title", chat.title, onDismiss = { dialog = "" }) { t ->
            scope.launch { report(Repo.call("setChatTitle", mapOf("chat_id" to chatId, "title" to t)), "Title changed") }
        }
        "desc" -> TextInputDialog(
            "Change description", "Description", info?.optString("description").orEmpty(), singleLine = false,
            onDismiss = { dialog = "" },
        ) { t -> scope.launch { report(Repo.call("setChatDescription", mapOf("chat_id" to chatId, "description" to t)), "Description changed") } }
        "userid" -> TextInputDialog("User ID", "Numeric user ID", onDismiss = { dialog = "" }) { v ->
            val uid = v.trim().toLongOrNull()
            if (uid == null) say("Invalid ID") else userMenu = uid.also { dialog = "" }
        }
        "perms" -> {
            ConfirmDialog(
                "Default permissions", "Allow or deny all sending permissions for regular members in this group.",
                confirmText = "Allow all", onDismiss = { dialog = "" },
            ) {
                scope.launch { report(Repo.call("setChatPermissions", mapOf("chat_id" to chatId, "permissions" to allPerms(true))), "Permissions updated") }
            }
        }
    }
}

private fun allPerms(v: Boolean) = JSONObject().apply {
    listOf(
        "can_send_messages", "can_send_audios", "can_send_documents", "can_send_photos", "can_send_videos",
        "can_send_video_notes", "can_send_voice_notes", "can_send_polls", "can_send_other_messages", "can_add_web_page_previews",
    ).forEach { put(it, v) }
}

@Composable
private fun Section(t: String) {
    Text(
        t, Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun Box2(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Box { content() }
}
