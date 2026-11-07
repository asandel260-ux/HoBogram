package com.botgram.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.webkit.MimeTypeMap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import org.json.JSONArray
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

private val AVATAR_COLORS = listOf(
    0xFFE57373, 0xFFBA68C8, 0xFF7986CB, 0xFF4FC3F7, 0xFF4DB6AC, 0xFF81C784, 0xFFFFB74D, 0xFFA1887F,
)

fun seedColor(seed: Long): Color = Color(AVATAR_COLORS[(abs(seed) % AVATAR_COLORS.size).toInt()])

@Composable
fun Avatar(name: String, seed: Long, size: Dp = 48.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(seedColor(seed)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().take(1).uppercase().ifEmpty { "?" },
            color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.4f).sp,
        )
    }
}

// ---------- time ----------

private val zone get() = ZoneId.systemDefault()
private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
private val dayFmt = DateTimeFormatter.ofPattern("EEEE, MMM d")
private val shortFmt = DateTimeFormatter.ofPattern("MMM d")
private val oldFmt = DateTimeFormatter.ofPattern("dd.MM.yy")

fun fmtTime(ms: Long): String = Instant.ofEpochMilli(ms).atZone(zone).format(timeFmt)

fun fmtDay(ms: Long): String {
    val d = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when (d) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> d.format(dayFmt)
    }
}

fun fmtListTime(ms: Long): String {
    if (ms <= 0) return ""
    val z = Instant.ofEpochMilli(ms).atZone(zone)
    val today = LocalDate.now(zone)
    return when {
        z.toLocalDate() == today -> z.format(timeFmt)
        z.year == today.year -> z.format(shortFmt)
        else -> z.format(oldFmt)
    }
}

fun sameDay(a: Long, b: Long) =
    Instant.ofEpochMilli(a).atZone(zone).toLocalDate() == Instant.ofEpochMilli(b).atZone(zone).toLocalDate()

// ---------- rich text ----------

/** Converts Telegram message entities (UTF-16 offsets, same as Kotlin strings) into styled text. */
fun buildFormatted(text: String, entJson: String?, linkColor: Color): AnnotatedString {
    if (entJson.isNullOrBlank()) return AnnotatedString(text)
    val arr = try {
        JSONArray(entJson)
    } catch (e: Exception) {
        return AnnotatedString(text)
    }
    return buildAnnotatedString {
        append(text)
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            val off = e.optInt("offset")
            val end = (off + e.optInt("length")).coerceAtMost(text.length)
            if (off < 0 || off >= end) continue
            val linkStyle = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
            when (e.optString("type")) {
                "bold" -> addStyle(SpanStyle(fontWeight = FontWeight.Bold), off, end)
                "italic" -> addStyle(SpanStyle(fontStyle = FontStyle.Italic), off, end)
                "underline" -> addStyle(SpanStyle(textDecoration = TextDecoration.Underline), off, end)
                "strikethrough" -> addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), off, end)
                "code", "pre" -> addStyle(
                    SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x33888888)), off, end,
                )
                "spoiler" -> addStyle(SpanStyle(background = Color(0x55888888)), off, end)
                "text_link" -> {
                    val url = e.optString("url")
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        addLink(LinkAnnotation.Url(url, linkStyle), off, end)
                    } else addStyle(SpanStyle(color = linkColor), off, end)
                }
                "url" -> {
                    val raw = text.substring(off, end)
                    val url = if (raw.startsWith("http", true)) raw else "https://$raw"
                    addLink(LinkAnnotation.Url(url, linkStyle), off, end)
                }
                "mention", "hashtag", "cashtag", "bot_command", "email", "phone_number", "text_mention" ->
                    addStyle(SpanStyle(color = linkColor), off, end)
            }
        }
    }
}

// ---------- files ----------

fun openDownloaded(ctx: Context, f: File, kind: String): Boolean {
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    val ext = f.extension.lowercase()
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        ?: when (kind) {
            "voice", "audio" -> "audio/*"
            "video", "video_note", "animation" -> "video/*"
            "photo" -> "image/*"
            else -> "*/*"
        }
    return try {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}

// ---------- voice ----------

class VoiceRecorder(private val ctx: Context) {
    private var rec: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    fun start() {
        val f = File(ctx.cacheDir, "voice_${System.currentTimeMillis()}.ogg")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.OGG)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
        r.setAudioSamplingRate(48000)
        r.setAudioEncodingBitRate(32000)
        r.setOutputFile(f.absolutePath)
        r.prepare()
        r.start()
        rec = r
        file = f
        startedAt = System.currentTimeMillis()
    }

    /** Returns the recorded file and its duration in seconds, or null if the recording was too short. */
    fun stop(): Pair<File, Int>? {
        val r = rec ?: return null
        val f = file
        rec = null
        file = null
        return try {
            r.stop()
            r.release()
            if (f == null) null else f to ((System.currentTimeMillis() - startedAt) / 1000).toInt().coerceAtLeast(1)
        } catch (e: RuntimeException) {
            r.release()
            f?.delete()
            null
        }
    }
}

// ---------- dialogs ----------

@Composable
fun TextInputDialog(
    title: String,
    label: String,
    initial: String = "",
    singleLine: Boolean = true,
    confirmText: String = "OK",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value, onValueChange = { value = it }, label = { Text(label) },
                singleLine = singleLine, minLines = if (singleLine) 1 else 3, maxLines = if (singleLine) 1 else 8,
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(value); onDismiss() }) { Text(confirmText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmText: String = "Confirm",
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirmText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
