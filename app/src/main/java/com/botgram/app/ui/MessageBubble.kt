package com.botgram.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.botgram.app.data.Msg
import com.botgram.app.data.Repo
import com.botgram.app.data.objects
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun DayChip(ms: Long) {
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Text(
                fmtDay(ms), Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun ServiceLine(text: String) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)) {
            Text(
                text, Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    m: Msg,
    showSender: Boolean,
    onLong: () -> Unit,
    onReplyClick: (Int) -> Unit,
    onImage: (String) -> Unit,
    onFile: (Msg) -> Unit,
    onNote: (String) -> Unit,
) {
    if (m.service) {
        ServiceLine(m.text)
        return
    }
    val bubble = if (m.out) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val shape = if (m.out) RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp) else RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
    val link = MaterialTheme.colorScheme.primary

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = if (m.out) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = bubble, shape = shape,
            modifier = Modifier.widthIn(max = 320.dp).clip(shape).combinedClickable(onClick = {}, onLongClick = onLong),
        ) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                if (showSender && !m.out && m.fromName.isNotEmpty()) {
                    Text(m.fromName, color = seedColor(m.fromId), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
                }
                if (m.replyPreview != null && m.replyToId != null) {
                    Row(
                        Modifier.padding(vertical = 3.dp).height(IntrinsicSize.Min)
                            .clickable { onReplyClick(m.replyToId) },
                    ) {
                        Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
                        Text(
                            m.replyPreview, Modifier.padding(start = 6.dp), maxLines = 2, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                MediaContent(m, onImage, onFile)
                val showText = m.text.isNotEmpty() && m.kind != "poll" && !(m.kind == "sticker" && m.extra == "static")
                if (showText) {
                    if (m.kind == "sticker") {
                        Text(m.text, fontSize = 48.sp)
                    } else {
                        Text(buildFormatted(m.text, m.entities, link), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                m.keyboard?.let { KeyboardView(it, onNote) }
                Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    if (m.edited) Text("edited  ", style = MaterialTheme.typography.labelSmall)
                    Text(fmtTime(m.date), style = MaterialTheme.typography.labelSmall)
                    if (m.out) Icon(Icons.Default.Done, null, Modifier.padding(start = 3.dp).size(13.dp))
                }
            }
        }
    }
}

@Composable
fun RemoteImage(fileId: String?, modifier: Modifier = Modifier, onClick: ((String) -> Unit)? = null) {
    val url by produceState<String?>(null, fileId) {
        value = if (fileId == null) null else Repo.fileUrl(fileId)
    }
    val u = url
    if (u == null) {
        Box(modifier.size(width = 200.dp, height = 140.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        }
    } else {
        AsyncImage(
            model = u, contentDescription = null, contentScale = ContentScale.Fit,
            modifier = modifier.then(if (onClick != null) Modifier.clickable { onClick(u) } else Modifier),
        )
    }
}

@Composable
private fun FileChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.padding(vertical = 4.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
        Text(label, Modifier.padding(start = 8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MediaContent(m: Msg, onImage: (String) -> Unit, onFile: (Msg) -> Unit) {
    val ctx = LocalContext.current
    val imgMod = Modifier.widthIn(max = 280.dp).heightIn(max = 360.dp).clip(RoundedCornerShape(10.dp))
    when (m.kind) {
        "photo" -> RemoteImage(m.fileId, imgMod, onImage)
        "sticker" -> if (m.extra == "static") RemoteImage(m.fileId, Modifier.widthIn(max = 160.dp).heightIn(max = 160.dp))
        "video" -> FileChip(Icons.Default.PlayCircle, m.fileName ?: "Video — tap to open") { onFile(m) }
        "animation" -> FileChip(Icons.Default.PlayCircle, m.fileName ?: "GIF — tap to open") { onFile(m) }
        "video_note" -> FileChip(Icons.Default.PlayCircle, "Video message — tap to open") { onFile(m) }
        "voice" -> FileChip(Icons.Default.Mic, "Voice message — tap to play") { onFile(m) }
        "audio" -> FileChip(Icons.Default.AudioFile, m.fileName ?: "Audio — tap to play") { onFile(m) }
        "document" -> FileChip(Icons.Default.Description, m.fileName ?: "Document — tap to open") { onFile(m) }
        "location" -> m.extra?.let { ll ->
            Text(
                "Open in maps", color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clickable {
                    try {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$ll?q=$ll")))
                    } catch (e: Exception) {
                        // no maps app
                    }
                }.padding(vertical = 2.dp),
            )
        }
        "poll" -> PollView(m.extra)
    }
}

@Composable
private fun PollView(extra: String?) {
    val poll = try {
        JSONObject(extra ?: return)
    } catch (e: Exception) {
        return
    }
    val total = poll.optInt("total_voter_count").coerceAtLeast(0)
    Column(Modifier.widthIn(min = 220.dp).padding(vertical = 4.dp)) {
        Text("📊 " + poll.optString("question"), fontWeight = FontWeight.SemiBold)
        Text(
            (if (poll.optBoolean("is_closed")) "Closed · " else "") +
                (if (poll.optBoolean("is_anonymous", true)) "Anonymous" else "Public") +
                (if (poll.optBoolean("allows_multiple_answers")) " · multiple answers" else ""),
            style = MaterialTheme.typography.labelSmall,
        )
        (poll.optJSONArray("options") ?: JSONArray()).objects().forEach { o ->
            val votes = o.optInt("voter_count")
            Column(Modifier.padding(top = 6.dp)) {
                Row {
                    Text(o.optString("text"), Modifier.weight(1f))
                    Text("$votes", style = MaterialTheme.typography.labelMedium)
                }
                LinearProgressIndicator(
                    progress = { if (total == 0) 0f else votes.toFloat() / total },
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                )
            }
        }
        Text("$total votes", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun KeyboardView(json: String, onNote: (String) -> Unit) {
    val rows = try {
        JSONArray(json)
    } catch (e: Exception) {
        return
    }
    val uri = LocalUriHandler.current
    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (i in 0 until rows.length()) {
            val row = rows.optJSONArray(i) ?: continue
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (b in row.objects()) {
                    OutlinedButton(
                        onClick = {
                            val url = b.optString("url")
                            if (url.startsWith("http")) {
                                try {
                                    uri.openUri(url)
                                } catch (e: Exception) {
                                    onNote("Cannot open $url")
                                }
                            } else if (b.has("callback_data")) {
                                onNote("Callback button: ${b.optString("callback_data")} (pressed by users, not from here)")
                            } else onNote("Button: ${b.optString("text")}")
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) { Text(b.optString("text"), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
    }
}
