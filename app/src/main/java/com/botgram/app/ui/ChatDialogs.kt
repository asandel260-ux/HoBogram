package com.botgram.app.ui

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Gif
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Poll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.botgram.app.data.Chat
import com.botgram.app.data.Msg

data class Attachment(val uri: Uri, val kind: String)

private val KIND_LABELS = listOf(
    "photo" to "Photo", "video" to "Video", "document" to "File", "audio" to "Audio",
    "animation" to "GIF", "voice" to "Voice", "video_note" to "Round video", "sticker" to "Sticker",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AttachmentDialog(att: Attachment, onKind: (String) -> Unit, onDismiss: () -> Unit, onSend: (String) -> Unit) {
    var caption by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send attachment") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Send as", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    KIND_LABELS.forEach { (k, v) ->
                        FilterChip(selected = att.kind == k, onClick = { onKind(k) }, label = { Text(v) })
                    }
                }
                if (att.kind != "sticker" && att.kind != "video_note") {
                    OutlinedTextField(
                        value = caption, onValueChange = { caption = it },
                        label = { Text("Caption (optional)") }, maxLines = 4,
                    )
                }
                Text(
                    "Bots can upload up to 50 MB. Stickers must be .webp, round videos square MP4, voice OGG/Opus, MP3 or M4A.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSend(caption); onDismiss() }) { Text("Send") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun PollDialog(onDismiss: () -> Unit, onSend: (String, List<String>, Boolean, Boolean) -> Unit) {
    var q by remember { mutableStateOf("") }
    var opts by remember { mutableStateOf("") }
    var anon by remember { mutableStateOf(true) }
    var multi by remember { mutableStateOf(false) }
    val list = opts.lines().map { it.trim() }.filter { it.isNotEmpty() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New poll") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = q, onValueChange = { q = it }, label = { Text("Question") })
                OutlinedTextField(
                    value = opts, onValueChange = { opts = it }, label = { Text("Options (one per line, 2–12)") },
                    minLines = 3, maxLines = 8,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = anon, onCheckedChange = { anon = it }); Text("Anonymous")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = multi, onCheckedChange = { multi = it }); Text("Multiple answers")
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = q.isNotBlank() && list.size in 2..12,
                onClick = { onSend(q.trim(), list, anon, multi); onDismiss() },
            ) { Text("Send") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun LocationDialog(onDismiss: () -> Unit, onSend: (Double, Double) -> Unit) {
    var lat by remember { mutableStateOf("") }
    var lon by remember { mutableStateOf("") }
    val la = lat.trim().toDoubleOrNull()
    val lo = lon.trim().toDoubleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send location") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = lat, onValueChange = { lat = it }, label = { Text("Latitude") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(
                    value = lon, onValueChange = { lon = it }, label = { Text("Longitude") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = la != null && lo != null && la in -90.0..90.0 && lo in -180.0..180.0,
                onClick = { onSend(la!!, lo!!); onDismiss() },
            ) { Text("Send") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ContactDialog(onDismiss: () -> Unit, onSend: (String, String, String) -> Unit) {
    var phone by remember { mutableStateOf("") }
    var first by remember { mutableStateOf("") }
    var last by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send contact") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = phone, onValueChange = { phone = it }, label = { Text("Phone number") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                )
                OutlinedTextField(value = first, onValueChange = { first = it }, label = { Text("First name") }, singleLine = true)
                OutlinedTextField(value = last, onValueChange = { last = it }, label = { Text("Last name (optional)") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(
                enabled = phone.isNotBlank() && first.isNotBlank(),
                onClick = { onSend(phone.trim(), first.trim(), last.trim()); onDismiss() },
            ) { Text("Send") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiceDialog(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send animated emoji") },
        text = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("🎲", "🎯", "🏀", "⚽", "🎳", "🎰").forEach {
                    TextButton(onClick = { onPick(it); onDismiss() }) { Text(it, fontSize = 32.sp) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
fun KeyboardDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Inline keyboard") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = v, onValueChange = { v = it }, minLines = 4, maxLines = 10,
                    placeholder = { Text("Yes|yes ; No|no\nWebsite|https://example.com") },
                )
                Text(
                    "One row per line. Separate buttons with ;  and label from payload with | . " +
                        "A payload starting with http(s):// or tg:// opens a link, anything else is sent to your bot as callback data. " +
                        "Attached to your next text message.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(v); onDismiss() }) { Text("Save") } },
        dismissButton = {
            TextButton(onClick = { onSave(""); onDismiss() }) { Text("Clear") }
        },
    )
}

@Composable
fun ForwardDialog(chats: List<Chat>, onDismiss: () -> Unit, onPick: (Chat) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Forward to…") },
        text = {
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(chats, key = { it.id }) { c ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(c); onDismiss() }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(c.title, c.id, 36.dp)
                        Text(c.title, Modifier.padding(start = 12.dp), fontWeight = FontWeight.Medium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReactionDialog(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("React") },
        text = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("👍", "👎", "❤", "🔥", "🥰", "👏", "😁", "🤔", "😢", "🎉", "🤯", "🙏").forEach {
                    TextButton(onClick = { onPick(it); onDismiss() }) { Text(it, fontSize = 28.sp) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AttachSheet(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val items = listOf(
        Triple("photo", "Photo", Icons.Default.Image),
        Triple("video", "Video", Icons.Default.Videocam),
        Triple("document", "File", Icons.Default.Description),
        Triple("audio", "Audio", Icons.Default.AudioFile),
        Triple("animation", "GIF", Icons.Default.Gif),
        Triple("location", "Location", Icons.Default.LocationOn),
        Triple("contact", "Contact", Icons.Default.Person),
        Triple("poll", "Poll", Icons.Default.Poll),
        Triple("dice", "Dice", Icons.Default.Casino),
        Triple("sticker", "Sticker", Icons.Default.EmojiEmotions),
    )
    ModalBottomSheet(onDismissRequest = onDismiss) {
        FlowRow(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items.forEach { (k, label, icon) ->
                Column(
                    Modifier.clickable { onPick(k) }.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(icon, label, tint = MaterialTheme.colorScheme.primary)
                    Text(label, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageActionsSheet(m: Msg, onDismiss: () -> Unit, onAction: (String) -> Unit) {
    val editable = m.out && m.kind in setOf("text", "photo", "video", "document", "audio", "animation")
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            @Composable
            fun Item(key: String, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
                ListItem(
                    headlineContent = { Text(label) },
                    leadingContent = { Icon(icon, null) },
                    modifier = Modifier.clickable { onAction(key); onDismiss() },
                )
            }
            Item("reply", "Reply", Icons.AutoMirrored.Filled.Reply)
            if (m.text.isNotEmpty()) Item("copy", "Copy text", Icons.Default.ContentCopy)
            if (editable) Item("edit", "Edit", Icons.Default.Edit)
            Item("forward", "Forward", Icons.Default.Share)
            Item("react", "React", Icons.Default.EmojiEmotions)
            if (m.fileId != null) Item("download", "Download & open", Icons.Default.Download)
            Item("pin", "Pin in chat", Icons.Default.PushPin)
            Item("unpin", "Unpin", Icons.Default.RadioButtonUnchecked)
            if (m.kind == "poll" && m.out) Item("stop_poll", "Stop poll", Icons.Default.StopCircle)
            Item("copy_id", "Copy message ID (${m.id})", Icons.Default.Tag)
            Item("delete_all", "Delete for everyone", Icons.Default.Delete)
            Item("delete_local", "Delete locally only", Icons.Default.DeleteSweep)
        }
    }
}
