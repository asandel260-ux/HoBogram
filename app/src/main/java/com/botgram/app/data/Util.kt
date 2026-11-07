package com.botgram.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

fun Throwable.friendly(): String = when (this) {
    is TgException -> if (code == 401) "Invalid or revoked bot token" else (message ?: "Error $code")
    is UnknownHostException, is SocketTimeoutException, is ConnectException -> "Network error — check your connection"
    else -> message ?: javaClass.simpleName
}

/** Reads a content Uri into memory for a Bot API upload (limit: 50 MB). */
suspend fun readUpload(ctx: Context, uri: Uri): UploadFile = withContext(Dispatchers.IO) {
    var name = "file"
    ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (i >= 0 && c.moveToFirst()) name = c.getString(i) ?: name
    }
    val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: throw IllegalStateException("Cannot read file")
    if (bytes.size > 50 * 1024 * 1024) throw IllegalStateException("File is larger than the 50 MB Bot API upload limit")
    UploadFile(name, mime, bytes)
}

/**
 * Inline keyboard mini-syntax: one row per line, buttons separated by ';', label and payload by '|'.
 * Payload starting with http(s):// or tg:// becomes a URL button, anything else callback data.
 */
fun parseKeyboard(spec: String): JSONObject? {
    val rows = JSONArray()
    for (line in spec.lines()) {
        if (line.isBlank()) continue
        val row = JSONArray()
        for (b in line.split(";")) {
            val parts = b.split("|", limit = 2)
            val label = parts[0].trim()
            if (label.isEmpty()) continue
            val payload = parts.getOrNull(1)?.trim().orEmpty().ifEmpty { label }
            val btn = JSONObject().put("text", label)
            if (payload.startsWith("http://") || payload.startsWith("https://") || payload.startsWith("tg://")) {
                btn.put("url", payload)
            } else {
                btn.put("callback_data", payload.take(64))
            }
            row.put(btn)
        }
        if (row.length() > 0) rows.put(row)
    }
    return if (rows.length() == 0) null else JSONObject().put("inline_keyboard", rows)
}
