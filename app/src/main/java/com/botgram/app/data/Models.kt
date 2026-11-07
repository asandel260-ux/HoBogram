package com.botgram.app.data

import org.json.JSONArray
import org.json.JSONObject

data class Account(val token: String, val id: Long, val username: String, val name: String, val sendOnly: Boolean = false)

data class Chat(
    val id: Long,
    val type: String, // private | group | supergroup | channel
    val title: String,
    val username: String? = null,
    val lastText: String = "",
    val lastDate: Long = 0,
    val unread: Int = 0,
    val pinned: Boolean = false,
    val left: Boolean = false, // bot removed from chat / user blocked the bot
) {
    val isPrivate get() = type == "private"
    val isChannel get() = type == "channel"
    val isGroup get() = type == "group" || type == "supergroup"
}

data class Msg(
    val chatId: Long,
    val id: Int,
    val fromId: Long,
    val fromName: String,
    val out: Boolean,
    val date: Long,
    val text: String = "",
    val kind: String = "text", // text photo video animation document audio voice video_note sticker location contact poll dice service
    val fileId: String? = null,
    val fileName: String? = null,
    val replyToId: Int? = null,
    val replyPreview: String? = null,
    val keyboard: String? = null, // inline_keyboard JSON array
    val entities: String? = null, // entities JSON array
    val extra: String? = null, // poll JSON, "lat,lon", "static"
    val edited: Boolean = false,
    val service: Boolean = false,
)

data class JoinReq(
    val chatId: Long,
    val userId: Long,
    val name: String,
    val username: String?,
    val date: Long,
    val bio: String?,
)

data class Conn(val kind: String, val detail: String = "") // connecting online offline conflict unauthorized

class Session(val account: Account, val api: BotApi, val store: Store)

// ---------- parsing ----------

fun userName(u: JSONObject?): String {
    if (u == null) return ""
    val n = (u.optString("first_name") + " " + u.optString("last_name")).trim()
    return n.ifBlank { u.optString("username") }
}

fun chatTitle(c: JSONObject): String {
    val t = c.optString("title")
    if (t.isNotBlank()) return t
    val n = (c.optString("first_name") + " " + c.optString("last_name")).trim()
    return n.ifBlank { c.optString("username").ifBlank { c.optLong("id").toString() } }
}

fun parseChat(c: JSONObject) = Chat(
    id = c.getLong("id"),
    type = c.optString("type", "private"),
    title = chatTitle(c),
    username = c.optString("username").ifBlank { null },
)

fun parseMsg(j: JSONObject, myId: Long): Msg {
    val chat = j.getJSONObject("chat")
    val from = j.optJSONObject("from")
    val sender = j.optJSONObject("sender_chat")
    val fromId = sender?.optLong("id") ?: from?.optLong("id") ?: 0L
    val fromName = if (sender != null) chatTitle(sender) else userName(from)

    var kind = "text"
    var text = j.optString("text")
    var entities: String? = j.optJSONArray("entities")?.toString()
    var fileId: String? = null
    var fileName: String? = null
    var extra: String? = null
    var service = false

    fun file(key: String, k: String) {
        kind = k
        val o = j.getJSONObject(key)
        fileId = o.optString("file_id")
        fileName = o.optString("file_name").ifEmpty { null }
    }

    fun svc(t: String) {
        kind = "service"; service = true; text = t
    }

    when {
        j.has("photo") -> {
            kind = "photo"
            val a = j.getJSONArray("photo")
            fileId = a.getJSONObject(a.length() - 1).optString("file_id")
        }
        j.has("video") -> file("video", "video")
        j.has("animation") -> file("animation", "animation")
        j.has("document") -> file("document", "document")
        j.has("audio") -> {
            file("audio", "audio")
            val a = j.getJSONObject("audio")
            if (fileName == null) {
                fileName = listOf(a.optString("performer"), a.optString("title"))
                    .filter { it.isNotEmpty() }.joinToString(" - ").ifEmpty { null }
            }
        }
        j.has("voice") -> file("voice", "voice")
        j.has("video_note") -> file("video_note", "video_note")
        j.has("sticker") -> {
            val st = j.getJSONObject("sticker")
            kind = "sticker"
            fileId = st.optString("file_id")
            text = st.optString("emoji")
            extra = if (!st.optBoolean("is_animated") && !st.optBoolean("is_video")) "static" else null
        }
        j.has("venue") -> {
            val v = j.getJSONObject("venue")
            val l = v.getJSONObject("location")
            kind = "location"
            text = "📍 ${v.optString("title")}\n${v.optString("address")}"
            extra = "${l.optDouble("latitude")},${l.optDouble("longitude")}"
        }
        j.has("location") -> {
            val l = j.getJSONObject("location")
            kind = "location"
            text = "📍 Location"
            extra = "${l.optDouble("latitude")},${l.optDouble("longitude")}"
        }
        j.has("contact") -> {
            val c = j.getJSONObject("contact")
            kind = "contact"
            text = "👤 ${c.optString("first_name")} ${c.optString("last_name")}".trim() +
                "\n📞 " + c.optString("phone_number")
        }
        j.has("poll") -> {
            val p = j.getJSONObject("poll")
            kind = "poll"
            text = p.optString("question")
            extra = p.toString()
        }
        j.has("dice") -> {
            val d = j.getJSONObject("dice")
            kind = "dice"
            text = "${d.optString("emoji")} ${d.optInt("value")}"
        }
        j.has("new_chat_members") -> {
            val a = j.getJSONArray("new_chat_members")
            svc("👋 " + (0 until a.length()).joinToString { userName(a.getJSONObject(it)) } + " joined")
        }
        j.has("left_chat_member") -> svc("🚪 ${userName(j.getJSONObject("left_chat_member"))} left")
        j.has("new_chat_title") -> svc("✏️ Title changed to “${j.optString("new_chat_title")}”")
        j.has("new_chat_photo") -> svc("🖼 Chat photo changed")
        j.has("delete_chat_photo") -> svc("🖼 Chat photo removed")
        j.has("group_chat_created") || j.has("supergroup_chat_created") -> svc("👥 Group created")
        j.has("channel_chat_created") -> svc("📢 Channel created")
        j.has("pinned_message") -> {
            val pm = j.getJSONObject("pinned_message")
            val prev = try {
                parseMsg(pm, myId).preview()
            } catch (e: Exception) {
                ""
            }
            svc("📌 Pinned: $prev")
        }
        j.has("message_auto_delete_timer_changed") -> svc("⏱ Auto-delete timer changed")
        j.has("migrate_to_chat_id") -> svc("⬆️ Group upgraded to supergroup")
        j.has("text") -> Unit
        else -> if (text.isEmpty() && !j.has("caption")) svc("(unsupported message type)")
    }

    val cap = j.optString("caption")
    if (cap.isNotEmpty() && kind != "service" && kind != "text") {
        text = cap
        entities = j.optJSONArray("caption_entities")?.toString()
    }

    val rep = j.optJSONObject("reply_to_message")
    val replyPrev = rep?.let {
        try {
            val r = parseMsg(it, myId)
            (if (r.fromName.isNotEmpty()) r.fromName + ": " else "") + r.preview()
        } catch (e: Exception) {
            null
        }
    }

    return Msg(
        chatId = chat.getLong("id"),
        id = j.getInt("message_id"),
        fromId = fromId,
        fromName = fromName,
        out = fromId == myId,
        date = j.optLong("date") * 1000,
        text = text,
        kind = kind,
        fileId = fileId,
        fileName = fileName,
        replyToId = rep?.optInt("message_id"),
        replyPreview = replyPrev,
        keyboard = j.optJSONObject("reply_markup")?.optJSONArray("inline_keyboard")?.toString(),
        entities = entities,
        extra = extra,
        edited = j.has("edit_date"),
        service = service,
    )
}

fun Msg.preview(): String = when (kind) {
    "text", "service", "contact", "dice" -> text
    "photo" -> "🖼 " + text.ifBlank { "Photo" }
    "video" -> "🎬 " + text.ifBlank { "Video" }
    "animation" -> "GIF " + text
    "document" -> "📎 " + (fileName ?: text.ifBlank { "File" })
    "audio" -> "🎵 " + (fileName ?: "Audio")
    "voice" -> "🎤 Voice message"
    "video_note" -> "⭕ Video message"
    "sticker" -> "$text Sticker"
    "location" -> text.lineSequence().firstOrNull().orEmpty()
    "poll" -> "📊 $text"
    else -> text
}

// ---------- persistence ----------

fun Chat.toJson(): JSONObject = JSONObject()
    .put("id", id).put("type", type).put("title", title).put("username", username)
    .put("lastText", lastText).put("lastDate", lastDate).put("unread", unread)
    .put("pinned", pinned).put("left", left)

private fun JSONObject.str(k: String): String? = if (has(k) && !isNull(k)) getString(k) else null
private fun JSONObject.int(k: String): Int? = if (has(k) && !isNull(k)) getInt(k) else null

fun chatFromJson(j: JSONObject) = Chat(
    id = j.getLong("id"), type = j.optString("type", "private"), title = j.optString("title"),
    username = j.str("username"), lastText = j.optString("lastText"), lastDate = j.optLong("lastDate"),
    unread = j.optInt("unread"), pinned = j.optBoolean("pinned"), left = j.optBoolean("left"),
)

fun Msg.toJson(): JSONObject = JSONObject()
    .put("chatId", chatId).put("id", id).put("fromId", fromId).put("fromName", fromName)
    .put("out", out).put("date", date).put("text", text).put("kind", kind)
    .put("fileId", fileId).put("fileName", fileName).put("replyToId", replyToId)
    .put("replyPreview", replyPreview).put("keyboard", keyboard).put("entities", entities)
    .put("extra", extra).put("edited", edited).put("service", service)

fun msgFromJson(j: JSONObject) = Msg(
    chatId = j.getLong("chatId"), id = j.getInt("id"), fromId = j.optLong("fromId"),
    fromName = j.optString("fromName"), out = j.optBoolean("out"), date = j.optLong("date"),
    text = j.optString("text"), kind = j.optString("kind", "text"), fileId = j.str("fileId"),
    fileName = j.str("fileName"), replyToId = j.int("replyToId"), replyPreview = j.str("replyPreview"),
    keyboard = j.str("keyboard"), entities = j.str("entities"), extra = j.str("extra"),
    edited = j.optBoolean("edited"), service = j.optBoolean("service"),
)

fun JoinReq.toJson(): JSONObject = JSONObject()
    .put("chatId", chatId).put("userId", userId).put("name", name)
    .put("username", username).put("date", date).put("bio", bio)

fun joinFromJson(j: JSONObject) = JoinReq(
    j.getLong("chatId"), j.getLong("userId"), j.optString("name"), j.str("username"), j.optLong("date"), j.str("bio"),
)

fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
