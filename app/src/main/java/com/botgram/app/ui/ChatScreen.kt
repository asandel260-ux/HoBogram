package com.botgram.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardAlt
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.botgram.app.data.Chat
import com.botgram.app.data.Msg
import com.botgram.app.data.Repo
import com.botgram.app.data.friendly
import com.botgram.app.data.parseKeyboard
import com.botgram.app.data.preview
import com.botgram.app.data.readUpload
import com.botgram.app.service.Notifier
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private enum class Pending { None, Reply, Edit }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(chatId: Long, onBack: () -> Unit, onInfo: () -> Unit) {
    val sess by Repo.session.collectAsState()
    val session = sess ?: return
    val store = session.store
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    val parseMode by Repo.parseMode.collectAsState()

    val chats by store.chats.collectAsState()
    val allMessages by store.messages.collectAsState()
    val chat = chats.firstOrNull { it.id == chatId } ?: Chat(chatId, "private", chatId.toString())
    val messages = allMessages[chatId].orEmpty()

    var input by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(Pending.None) }
    var target by remember { mutableStateOf<Msg?>(null) }
    var keyboardSpec by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<Msg?>(null) }
    var forwardMsg by remember { mutableStateOf<Msg?>(null) }
    var reactMsg by remember { mutableStateOf<Msg?>(null) }
    var showAttach by remember { mutableStateOf(false) }
    var attachment by remember { mutableStateOf<Attachment?>(null) }
    var dialog by remember { mutableStateOf("") }
    var viewer by remember { mutableStateOf<String?>(null) }
    var recording by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val recorder = remember { VoiceRecorder(ctx) }
    val listState = rememberLazyListState()

    fun report(r: Result<*>) {
        r.onFailure { e -> scope.launch { snack.showSnackbar(e.friendly()) } }
    }

    // read tracking
    DisposableEffect(chatId) {
        Repo.openChatId = chatId
        Notifier.cancel(ctx, chatId)
        onDispose { if (Repo.openChatId == chatId) Repo.openChatId = null }
    }
    LaunchedEffect(chatId, messages.size) {
        store.markRead(chatId)
        Notifier.cancel(ctx, chatId)
        if (messages.isNotEmpty()) {
            val atEnd = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= listState.layoutInfo.totalItemsCount - 3 } ?: true
            if (atEnd || messages.last().out) listState.scrollToItem(messages.size - 1 + 0)
        }
    }

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        val k = pendingKind
        if (uri != null && k != null) attachment = Attachment(uri, k)
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) scope.launch { snack.showSnackbar("Microphone permission denied") }
    }

    fun pick(kind: String) {
        pendingKind = kind
        pickMedia.launch(
            when (kind) {
                "photo" -> "image/*"
                "video", "video_note" -> "video/*"
                "audio", "voice" -> "audio/*"
                "animation" -> "image/gif"
                "sticker" -> "image/webp"
                else -> "*/*"
            },
        )
    }

    fun sendText() {
        val text = input.trim()
        if (text.isEmpty() || sending) return
        val tgt = target
        val m = mode
        val spec = keyboardSpec
        sending = true
        scope.launch {
            if (m == Pending.Edit && tgt != null) {
                Repo.editMessage(chatId, tgt, text, parseMode).onSuccess { input = ""; mode = Pending.None; target = null }.also { report(it) }
            } else {
                val markup = if (spec.isBlank()) null else parseKeyboard(spec)
                Repo.sendText(chatId, text, if (m == Pending.Reply) tgt?.id else null, markup, parseMode)
                    .onSuccess { input = ""; mode = Pending.None; target = null; keyboardSpec = "" }.also { report(it) }
            }
            sending = false
        }
    }

    fun handleAction(m: Msg, key: String) {
        when (key) {
            "reply" -> { mode = Pending.Reply; target = m }
            "edit" -> { mode = Pending.Edit; target = m; input = m.text }
            "copy" -> clipboard.setText(AnnotatedString(m.text))
            "copy_id" -> clipboard.setText(AnnotatedString(m.id.toString()))
            "forward" -> forwardMsg = m
            "react" -> reactMsg = m
            "pin" -> scope.launch { report(Repo.call("pinChatMessage", mapOf("chat_id" to chatId, "message_id" to m.id))) }
            "unpin" -> scope.launch { report(Repo.call("unpinChatMessage", mapOf("chat_id" to chatId, "message_id" to m.id))) }
            "stop_poll" -> scope.launch { report(Repo.stopPoll(chatId, m.id)) }
            "delete_all" -> scope.launch { report(Repo.deleteMessage(chatId, m.id, true)) }
            "delete_local" -> scope.launch { Repo.deleteMessage(chatId, m.id, false) }
            "download" -> scope.launch {
                val id = m.fileId ?: return@launch
                snack.showSnackbar("Downloading…", duration = androidx.compose.material3.SnackbarDuration.Short)
                Repo.download(id, m.fileName).onSuccess {
                    if (!openDownloaded(ctx, it, m.kind)) snack.showSnackbar("Saved to ${it.name}, but no app can open it")
                }.onFailure { report(Result.failure<Unit>(it)) }
            }
        }
    }

    fun openFile(m: Msg) = handleAction(m, "download")

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {
                    Row(Modifier.clickable { onInfo() }, verticalAlignment = Alignment.CenterVertically) {
                        Avatar(chat.title, chat.id, 36.dp)
                        Column(Modifier.padding(start = 10.dp)) {
                            Text(chat.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                            Text(
                                (chat.username?.let { "@$it · " } ?: "") + chat.type,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Chat info & admin") }, onClick = { menuOpen = false; onInfo() })
                        DropdownMenuItem(text = { Text("Pinned message") }, onClick = {
                            menuOpen = false
                            scope.launch {
                                Repo.call("getChat", mapOf("chat_id" to chatId)).onSuccess {
                                    val pm = (it as? JSONObject)?.optJSONObject("pinned_message")
                                    snack.showSnackbar(
                                        if (pm == null) "No pinned message"
                                        else "📌 " + com.botgram.app.data.parseMsg(pm, session.account.id).preview(),
                                    )
                                }.onFailure { report(Result.failure<Unit>(it)) }
                            }
                        })
                        if (chat.type == "supergroup" || chat.type == "channel") {
                            DropdownMenuItem(text = { Text("Load older messages") }, onClick = {
                                menuOpen = false
                                scope.launch {
                                    Repo.loadOlder(chatId)
                                        .onSuccess { snack.showSnackbar(if (it == 0) "No older messages found" else "Loaded $it older messages") }
                                        .onFailure { report(Result.failure<Unit>(it)) }
                                }
                            })
                        }
                        DropdownMenuItem(text = { Text("Clear local history") }, onClick = { menuOpen = false; confirmClear = true })
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            Column(Modifier.imePadding().navigationBarsPadding()) {
                if (mode != Pending.None && target != null) {
                    Surface(tonalElevation = 2.dp) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (mode == Pending.Edit) "Editing message" else "Reply to ${target!!.fromName.ifEmpty { "message" }}",
                                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                                )
                                Text(target!!.preview(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = { if (mode == Pending.Edit) input = ""; mode = Pending.None; target = null }) {
                                Icon(Icons.Default.Close, "Cancel")
                            }
                        }
                    }
                }
                if (keyboardSpec.isNotBlank()) {
                    Text(
                        "Inline keyboard attached to next message", Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                    )
                }
                Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.Bottom) {
                    if (mode != Pending.Edit) {
                        IconButton(onClick = { showAttach = true }) { Icon(Icons.Default.AttachFile, "Attach") }
                    }
                    OutlinedTextField(
                        value = input,
                        onValueChange = {
                            if (input.isEmpty() && it.isNotEmpty()) Repo.typing(chatId)
                            input = it
                        },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(if (recording) "Recording… release to send" else "Message") },
                        maxLines = 6,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    )
                    if (mode != Pending.Edit) {
                        IconButton(onClick = { dialog = "keyboard" }) { Icon(Icons.Default.KeyboardAlt, "Inline keyboard") }
                    }
                    if (input.isNotBlank()) {
                        FilledIconButton(onClick = { sendText() }, enabled = !sending) {
                            Icon(Icons.AutoMirrored.Filled.Send, "Send")
                        }
                    } else {
                        Box(
                            Modifier.size(48.dp).clip(CircleShape)
                                .background(if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                                .pointerInput(chatId) {
                                    detectTapGestures(onPress = {
                                        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                            micPermission.launch(Manifest.permission.RECORD_AUDIO)
                                            return@detectTapGestures
                                        }
                                        try {
                                            recorder.start()
                                            recording = true
                                        } catch (e: Exception) {
                                            scope.launch { snack.showSnackbar("Cannot start recording") }
                                            return@detectTapGestures
                                        }
                                        tryAwaitRelease()
                                        recording = false
                                        val r = recorder.stop()
                                        if (r == null) {
                                            scope.launch { snack.showSnackbar("Hold to record a voice message") }
                                        } else {
                                            val (file, secs) = r
                                            scope.launch {
                                                val up = com.botgram.app.data.UploadFile(file.name, "audio/ogg", file.readBytes())
                                                report(Repo.sendMedia(chatId, "voice", up, "", null, null, secs))
                                                file.delete()
                                            }
                                        }
                                    })
                                },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Default.Mic, "Hold to record", tint = MaterialTheme.colorScheme.onPrimary) }
                    }
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (messages.isEmpty()) {
                Text(
                    "No messages stored yet.\nThe Bot API doesn't expose older history — only messages received or sent while BotGram is connected appear here.",
                    Modifier.align(Alignment.Center).padding(32.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
                items(messages.size, key = { messages[it].id }) { i ->
                    val m = messages[i]
                    if (i == 0 || !sameDay(messages[i - 1].date, m.date)) DayChip(m.date)
                    MessageBubble(
                        m = m,
                        showSender = !chat.isPrivate,
                        onLong = { if (!m.service) actionsFor = m },
                        onReplyClick = { id ->
                            val idx = messages.indexOfFirst { it.id == id }
                            if (idx >= 0) scope.launch { listState.animateScrollToItem(idx) }
                        },
                        onImage = { viewer = it },
                        onFile = { openFile(it) },
                        onNote = { n -> scope.launch { snack.showSnackbar(n) } },
                    )
                }
            }
            val atBottom by remember {
                androidx.compose.runtime.derivedStateOf {
                    val info = listState.layoutInfo
                    val last = info.visibleItemsInfo.lastOrNull()
                    last == null || last.index >= info.totalItemsCount - 2
                }
            }
            if (!atBottom && messages.isNotEmpty()) {
                FloatingActionButton(
                    onClick = { scope.launch { listState.animateScrollToItem(messages.size - 1) } },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).size(44.dp),
                ) { Icon(Icons.Default.ArrowDownward, "Latest") }
            }
        }
    }

    // ---- dialogs / sheets ----
    actionsFor?.let { m ->
        MessageActionsSheet(m, onDismiss = { actionsFor = null }, onAction = { handleAction(m, it) })
    }
    if (showAttach) {
        AttachSheet(onDismiss = { showAttach = false }, onPick = { k ->
            showAttach = false
            when (k) {
                "location", "contact", "poll", "dice" -> dialog = k
                else -> pick(k)
            }
        })
    }
    attachment?.let { a ->
        AttachmentDialog(
            att = a, onKind = { attachment = a.copy(kind = it) },
            onDismiss = { attachment = null },
            onSend = { caption ->
                val replyId = if (mode == Pending.Reply) target?.id else null
                scope.launch {
                    sending = true
                    try {
                        val up = readUpload(ctx, a.uri)
                        Repo.sendMedia(chatId, a.kind, up, caption, replyId, parseMode).onSuccess {
                            if (replyId != null) { mode = Pending.None; target = null }
                        }.also { report(it) }
                    } catch (e: Exception) {
                        snack.showSnackbar(e.friendly())
                    }
                    sending = false
                }
            },
        )
    }
    when (dialog) {
        "poll" -> PollDialog({ dialog = "" }) { q, o, an, mu -> scope.launch { report(Repo.sendPoll(chatId, q, o, an, mu)) } }
        "location" -> LocationDialog({ dialog = "" }) { la, lo ->
            scope.launch { report(Repo.sendLocation(chatId, la, lo, if (mode == Pending.Reply) target?.id else null)) }
        }
        "contact" -> ContactDialog({ dialog = "" }) { p, f, l -> scope.launch { report(Repo.sendContact(chatId, p, f, l)) } }
        "dice" -> DiceDialog({ dialog = "" }) { e -> scope.launch { report(Repo.sendDice(chatId, e)) } }
        "keyboard" -> KeyboardDialog(keyboardSpec, { dialog = "" }) { keyboardSpec = it }
    }
    forwardMsg?.let { m ->
        ForwardDialog(chats.filter { it.id != chatId || true }, onDismiss = { forwardMsg = null }) { dest ->
            scope.launch {
                Repo.forward(chatId, m.id, dest.id).onSuccess { snack.showSnackbar("Forwarded to ${dest.title}") }
                    .onFailure { report(Result.failure<Unit>(it)) }
            }
        }
    }
    reactMsg?.let { m ->
        ReactionDialog(onDismiss = { reactMsg = null }) { emoji ->
            scope.launch {
                report(
                    Repo.call(
                        "setMessageReaction",
                        mapOf(
                            "chat_id" to chatId, "message_id" to m.id,
                            "reaction" to JSONArray().put(JSONObject().put("type", "emoji").put("emoji", emoji)),
                        ),
                    ),
                )
            }
        }
    }
    viewer?.let { url ->
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { viewer = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black).clickable { viewer = null }, contentAlignment = Alignment.Center) {
                AsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxSize())
            }
        }
    }
    if (confirmClear) {
        ConfirmDialog("Clear local history?", "This only removes the copy stored on this device. Messages in Telegram stay.", "Clear",
            onDismiss = { confirmClear = false }) { store.clearMessages(chatId) }
    }
}

private var pendingKind: String? = null
