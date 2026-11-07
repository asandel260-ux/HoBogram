package com.botgram.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.botgram.app.data.Chat
import com.botgram.app.data.Repo
import com.botgram.app.data.friendly
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatListScreen(
    onChat: (Long) -> Unit,
    onAddBot: () -> Unit,
    onSettings: () -> Unit,
    onBroadcast: () -> Unit,
    onConsole: () -> Unit,
) {
    val sess by Repo.session.collectAsState()
    val session = sess ?: return
    val store = session.store
    val chats by store.chats.collectAsState()
    val conn by Repo.conn.collectAsState()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }

    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("all") }
    var openDialog by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<Long?>(null) }
    val accounts = remember(sess) { Repo.accounts.list() }

    val shown = remember(chats, query, filter) {
        chats.filter {
            when (filter) {
                "private" -> it.isPrivate
                "groups" -> it.isGroup
                "channels" -> it.isChannel
                else -> true
            } && (query.isBlank() || it.title.contains(query, true) || (it.username ?: "").contains(query, true))
        }.sortedWith(compareByDescending<Chat> { it.pinned }.thenByDescending { it.lastDate })
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Avatar(session.account.name, session.account.id, 56.dp)
                        Text(session.account.name, style = MaterialTheme.typography.titleMedium)
                        Text("@${session.account.username}", style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                    accounts.forEach { a ->
                        NavigationDrawerItem(
                            label = { Text("@${a.username}") },
                            selected = a.id == session.account.id,
                            icon = { Icon(Icons.Default.SmartToy, null) },
                            onClick = {
                                scope.launch { drawer.close() }
                                if (a.id != session.account.id) Repo.activate(a)
                            },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                    NavigationDrawerItem(
                        label = { Text("Add another bot") }, selected = false,
                        icon = { Icon(Icons.Default.Add, null) },
                        onClick = { scope.launch { drawer.close() }; onAddBot() },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    NavigationDrawerItem(
                        label = { Text("Broadcast") }, selected = false,
                        icon = { Icon(Icons.Default.Campaign, null) },
                        onClick = { scope.launch { drawer.close() }; onBroadcast() },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    NavigationDrawerItem(
                        label = { Text("API console") }, selected = false,
                        icon = { Icon(Icons.Default.Terminal, null) },
                        onClick = { scope.launch { drawer.close() }; onConsole() },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    NavigationDrawerItem(
                        label = { Text("Bot settings") }, selected = false,
                        icon = { Icon(Icons.Default.Settings, null) },
                        onClick = { scope.launch { drawer.close() }; onSettings() },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Default.Menu, "Menu") }
                    },
                    title = {
                        if (searching) {
                            TextField(
                                value = query, onValueChange = { query = it },
                                placeholder = { Text("Search chats") }, singleLine = true,
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                    unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                                ),
                            )
                        } else {
                            Column {
                                Text(session.account.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    when (conn.kind) {
                                        "online" -> "@${session.account.username} · online" + if (session.account.mtproto) " (MTProto)" else ""
                                        "connecting" -> "connecting…"
                                        "sendonly" -> "@${session.account.username} · send-only (webhook kept)"
                                        "offline" -> "waiting for network…"
                                        "conflict" -> "conflict"
                                        else -> "signed out"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                            Icon(if (searching) Icons.Default.Close else Icons.Default.Search, "Search")
                        }
                    },
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = { openDialog = true }) { Icon(Icons.Default.Add, "Open chat") }
            },
            snackbarHost = { SnackbarHost(snack) },
        ) { pad ->
            Column(Modifier.padding(pad).fillMaxSize()) {
                if (conn.kind == "conflict" || conn.kind == "unauthorized") {
                    Row(
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (conn.kind == "unauthorized") "Cannot connect: ${conn.detail.ifBlank { "the token was revoked" }}. Check your token / api_id / api_hash in Bot settings."
                            else "Another client or a webhook is receiving this bot's updates.",
                            modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        if (conn.kind == "conflict") {
                            TextButton(onClick = {
                                scope.launch { Repo.removeWebhook().onFailure { snack.showSnackbar(it.friendly()) } }
                            }) { Text("Remove webhook") }
                        }
                    }
                }
                Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("all" to "All", "private" to "Private", "groups" to "Groups", "channels" to "Channels").forEach { (k, v) ->
                        FilterChip(selected = filter == k, onClick = { filter = k }, label = { Text(v) })
                    }
                }
                if (shown.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            if (chats.isEmpty())
                                (if (conn.kind == "sendonly") "Send-only mode: incoming messages go to your webhook. Tap + and enter a chat ID or @username to send messages.\n\n" else "") + "No chats yet.\n\nMessage your bot from Telegram, add it to a group, or tap + to open a chat by ID or @username (channels and supergroups the bot is in)."
                            else "Nothing matches.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(shown, key = { it.id }) { c ->
                            Box {
                                ChatRow(c, onClick = { onChat(c.id) }, onLong = { menuFor = c.id })
                                DropdownMenu(expanded = menuFor == c.id, onDismissRequest = { menuFor = null }) {
                                    DropdownMenuItem(
                                        text = { Text(if (c.pinned) "Unpin" else "Pin to top") },
                                        onClick = { store.setPinned(c.id, !c.pinned); menuFor = null },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Mark as read") },
                                        onClick = { store.markRead(c.id); menuFor = null },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Clear local history") },
                                        onClick = { store.clearMessages(c.id); menuFor = null },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Remove from list") },
                                        onClick = { store.removeChat(c.id); menuFor = null },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (openDialog) {
        TextInputDialog(
            title = "Open chat",
            label = "Chat ID or @username",
            confirmText = "Open",
            onDismiss = { openDialog = false },
            onConfirm = { input ->
                scope.launch {
                    Repo.openChat(input)
                        .onSuccess { onChat(it) }
                        .onFailure { snack.showSnackbar(it.friendly()) }
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(c: Chat, onClick: () -> Unit, onLong: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLong)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(c.title, c.id)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    c.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (c.left) Text("  · inactive", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            Text(
                c.lastText.ifBlank { c.username?.let { "@$it" } ?: c.type },
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(fmtListTime(c.lastDate), style = MaterialTheme.typography.labelSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (c.pinned) Icon(Icons.Default.PushPin, null, Modifier.size(14.dp))
                if (c.unread > 0) {
                    Box(
                        Modifier.padding(start = 4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    ) {
                        Text(
                            if (c.unread > 99) "99+" else c.unread.toString(),
                            color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}
