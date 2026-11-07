package com.botgram.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.botgram.app.service.Notifier
import com.botgram.app.service.PollingService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Process-wide state: accounts, the active bot session, the long-poll loop and every Bot API operation. */
object Repo {
    private const val TAG = "BotGram"
    private val ALLOWED = listOf(
        "message", "edited_message", "channel_post", "edited_channel_post", "callback_query",
        "my_chat_member", "chat_member", "chat_join_request", "poll",
    )

    lateinit var ctx: Context
    lateinit var accounts: Accounts
    private lateinit var prefs: SharedPreferences

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val session = MutableStateFlow<Session?>(null)
    val conn = MutableStateFlow(Conn("connecting"))
    val backgroundService = MutableStateFlow(true)
    val autoAnswer = MutableStateFlow(true)
    val parseMode = MutableStateFlow("")

    /** (accountId, chatId) requested by a notification tap. */
    val openRequest = MutableStateFlow<Pair<Long, Long>?>(null)

    @Volatile
    var foreground = false

    @Volatile
    var openChatId: Long? = null

    private var pollJob: kotlinx.coroutines.Job? = null
    private val urlCache = ConcurrentHashMap<String, String>()

    // ---------------------------------------------------------------- setup

    fun init(c: Context) {
        ctx = c.applicationContext
        accounts = Accounts(ctx)
        prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        backgroundService.value = prefs.getBoolean("bg", true)
        autoAnswer.value = prefs.getBoolean("auto_answer", true)
        parseMode.value = prefs.getString("parse_mode", "") ?: ""
        val list = accounts.list()
        val active = list.firstOrNull { it.id == accounts.activeId } ?: list.firstOrNull()
        if (active != null) activate(active)
    }

    fun setBackground(v: Boolean) {
        backgroundService.value = v
        prefs.edit().putBoolean("bg", v).apply()
        if (v && session.value != null) PollingService.start(ctx) else PollingService.stop(ctx)
    }

    fun setAutoAnswer(v: Boolean) {
        autoAnswer.value = v
        prefs.edit().putBoolean("auto_answer", v).apply()
    }

    fun setParseMode(v: String) {
        parseMode.value = v
        prefs.edit().putString("parse_mode", v).apply()
    }

    fun activate(acc: Account) {
        pollJob?.cancel()
        Mtproto.stop()
        urlCache.clear()
        accounts.activeId = acc.id
        val store = Store(File(ctx.filesDir, "store_${acc.id}.json"), scope).also { it.load() }
        val s = Session(acc, BotApi(acc.token), store)
        session.value = s
        if (acc.mtproto) {
            if (accounts.apiId == 0 || accounts.apiHash.isBlank()) {
                conn.value = Conn("unauthorized", "API ID / hash missing")
            } else {
                conn.value = Conn("connecting")
                Mtproto.start(s, accounts.apiId, accounts.apiHash)
            }
        } else if (acc.sendOnly) {
            conn.value = Conn("sendonly")
        } else {
            conn.value = Conn("connecting")
            startPolling(s)
        }
    }

    /** mode: "poll" (Bot API getUpdates, removes webhook), "mtproto" (TDLib, webhook untouched), "sendonly". */
    suspend fun setMode(mode: String): Result<Unit> = attempt {
        val s = cur()
        if (mode == "mtproto" && (accounts.apiId == 0 || accounts.apiHash.isBlank())) {
            error("Set your API ID and hash first (sign-in screen → MTProto settings)")
        }
        if (mode == "poll") s.api.call("deleteWebhook")
        val updated = s.account.copy(sendOnly = mode == "sendonly", mtproto = mode == "mtproto")
        accounts.add(updated)
        activate(updated)
    }

    suspend fun probe(token: String): Result<Pair<Account, Boolean>> = attempt {
        val api = BotApi(token)
        val me = api.callObj("getMe")
        val acc = Account(token, me.getLong("id"), me.optString("username"), me.optString("first_name"))
        val hook = api.callObj("getWebhookInfo").optString("url").isNotEmpty()
        acc to hook
    }

    suspend fun commitLogin(acc: Account, removeWebhook: Boolean, mode: String = "poll"): Result<Unit> = attempt {
        if (removeWebhook) BotApi(acc.token).call("deleteWebhook")
        val a = acc.copy(sendOnly = mode == "sendonly", mtproto = mode == "mtproto")
        accounts.add(a)
        activate(a)
    }

    fun logout(id: Long) {
        val current = session.value
        accounts.remove(id)
        File(ctx.filesDir, "store_$id.json").delete()
        if (current?.account?.id == id) Mtproto.stop()
        Mtproto.wipe(id)
        if (current?.account?.id == id) {
            pollJob?.cancel()
            val next = accounts.list().firstOrNull()
            if (next != null) {
                activate(next)
            } else {
                session.value = null
                accounts.activeId = 0
                PollingService.stop(ctx)
            }
        }
    }

    suspend fun removeWebhook(): Result<Unit> = attempt {
        cur().api.call("deleteWebhook")
        conn.value = Conn("connecting")
    }

    private fun cur(): Session = session.value ?: error("Not signed in")

    private suspend fun <T> attempt(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    // ---------------------------------------------------------------- polling

    private fun startPolling(s: Session) {
        pollJob?.cancel()
        pollJob = scope.launch {
            var backoff = 1500L
            while (isActive) {
                try {
                    val arr = s.api.callArr(
                        "getUpdates",
                        mapOf("offset" to s.store.offset, "timeout" to 25, "allowed_updates" to JSONArray(ALLOWED)),
                    )
                    if (conn.value.kind != "online") conn.value = Conn("online")
                    backoff = 1500
                    for (i in 0 until arr.length()) {
                        val u = arr.getJSONObject(i)
                        try {
                            handleUpdate(s, u)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.e(TAG, "update failed", e)
                        }
                        s.store.offset = u.getLong("update_id") + 1
                    }
                    if (arr.length() > 0) s.store.scheduleSave()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: TgException) {
                    when (e.code) {
                        401 -> {
                            conn.value = Conn("unauthorized", e.message.orEmpty())
                            return@launch
                        }
                        409 -> conn.value = Conn("conflict", e.message.orEmpty())
                        else -> conn.value = Conn("offline", e.message.orEmpty())
                    }
                    delay(if (e.code == 429) e.retryAfter * 1000L else backoff)
                    backoff = (backoff * 2).coerceAtMost(30_000)
                } catch (e: Exception) {
                    conn.value = Conn("offline", e.friendly())
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(30_000)
                }
            }
        }
    }

    private suspend fun handleUpdate(s: Session, u: JSONObject) {
        u.optJSONObject("message")?.let { onMessage(s, it, false) }
        u.optJSONObject("channel_post")?.let { onMessage(s, it, false) }
        u.optJSONObject("edited_message")?.let { onMessage(s, it, true) }
        u.optJSONObject("edited_channel_post")?.let { onMessage(s, it, true) }
        u.optJSONObject("callback_query")?.let { onCallback(s, it) }
        u.optJSONObject("my_chat_member")?.let { onMyChatMember(s, it) }
        u.optJSONObject("chat_member")?.let { onChatMember(s, it) }
        u.optJSONObject("chat_join_request")?.let { onJoinRequest(s, it) }
        u.optJSONObject("poll")?.let { s.store.updatePoll(it) }
    }

    private fun onMessage(s: Session, j: JSONObject, edited: Boolean) {
        val chat = parseChat(j.getJSONObject("chat"))
        val m = parseMsg(j, s.account.id).copy(edited = edited || j.has("edit_date"))
        deliver(s, chat, m, edited)
    }

    fun deliver(s: Session, chat: Chat, m: Msg, edited: Boolean) {
        val incoming = !m.out
        val viewing = foreground && openChatId == chat.id
        val countable = incoming && !edited && !m.service
        s.store.putMessage(chat, m, unread = countable && !viewing)
        if (countable && !viewing) Notifier.message(ctx, s.account, chat, m)
    }

    private suspend fun onCallback(s: Session, q: JSONObject) {
        val msg = q.optJSONObject("message")
        val from = q.optJSONObject("from")
        val who = userName(from)
        val data = q.optString("data").ifEmpty { q.optString("game_short_name") }
        if (msg != null) {
            val chat = parseChat(msg.getJSONObject("chat"))
            val note = Msg(
                chatId = chat.id, id = s.store.nextLocalId(), fromId = from?.optLong("id") ?: 0L,
                fromName = who, out = false, date = System.currentTimeMillis(),
                text = "🔘 $who pressed button: $data", kind = "service", service = true,
            )
            s.store.putMessage(chat, note, unread = false)
        }
        if (autoAnswer.value) {
            try {
                s.api.call("answerCallbackQuery", mapOf("callback_query_id" to q.getString("id")))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // callback may have expired
            }
        }
    }

    fun note(s: Session, chat: Chat, text: String, dateSec: Long, userId: Long = 0, name: String = "") =
        serviceNote(s, chat, text, dateSec, userId, name)

    private fun serviceNote(s: Session, chat: Chat, text: String, dateSec: Long, userId: Long = 0, name: String = "") {
        val note = Msg(
            chatId = chat.id, id = s.store.nextLocalId(), fromId = userId, fromName = name, out = false,
            date = if (dateSec > 0) dateSec * 1000 else System.currentTimeMillis(),
            text = text, kind = "service", service = true,
        )
        s.store.putMessage(chat, note, unread = false)
    }

    private fun onMyChatMember(s: Session, u: JSONObject) {
        val chat = parseChat(u.getJSONObject("chat"))
        val status = u.getJSONObject("new_chat_member").optString("status")
        val gone = status == "left" || status == "kicked"
        val who = userName(u.optJSONObject("from"))
        val text = when {
            chat.isPrivate && gone -> "🚫 $who blocked the bot"
            chat.isPrivate -> "✅ $who started the bot"
            gone -> "🚪 Bot was removed from this chat"
            status == "administrator" -> "⭐ Bot is now an administrator"
            else -> "✅ Bot is now a $status"
        }
        serviceNote(s, chat.copy(left = gone), text, u.optLong("date"))
    }

    private fun onChatMember(s: Session, u: JSONObject) {
        val chat = parseChat(u.getJSONObject("chat"))
        val nm = u.getJSONObject("new_chat_member")
        val user = nm.optJSONObject("user")
        val old = u.getJSONObject("old_chat_member").optString("status")
        val text = "👤 ${userName(user)}: $old → ${nm.optString("status")}"
        serviceNote(s, chat, text, u.optLong("date"), user?.optLong("id") ?: 0, userName(user))
    }

    private fun onJoinRequest(s: Session, u: JSONObject) {
        val chat = parseChat(u.getJSONObject("chat"))
        val from = u.getJSONObject("from")
        s.store.addJoin(
            JoinReq(
                chat.id, from.getLong("id"), userName(from), from.optString("username").ifBlank { null },
                u.optLong("date") * 1000, u.optString("bio").ifBlank { null },
            ),
        )
        serviceNote(s, chat, "🙋 ${userName(from)} requested to join", u.optLong("date"))
    }

    // ---------------------------------------------------------------- generic call

    suspend fun call(method: String, params: Map<String, Any?> = emptyMap(), files: Map<String, UploadFile> = emptyMap()): Result<Any?> =
        attempt { cur().api.call(method, params, files) }

    suspend fun rawCall(method: String, json: String): Result<String> = attempt {
        val body = if (json.isBlank()) JSONObject() else JSONObject(json)
        val params = HashMap<String, Any?>()
        for (k in body.keys()) params[k] = body.get(k)
        when (val r = cur().api.call(method.trim().removePrefix("/"), params)) {
            is JSONObject -> r.toString(2)
            is JSONArray -> r.toString(2)
            else -> r.toString()
        }
    }

    private fun ingest(s: Session, r: Any?, edited: Boolean = false) {
        val j = r as? JSONObject ?: return
        if (!j.has("chat") || !j.has("message_id")) return
        val m = parseMsg(j, s.account.id).copy(edited = edited || j.has("edit_date"))
        s.store.putMessage(parseChat(j.getJSONObject("chat")), m, unread = false)
    }

    private fun replyParams(id: Int?): JSONObject? =
        id?.let { JSONObject().put("message_id", it).put("allow_sending_without_reply", true) }

    // ---------------------------------------------------------------- sending

    suspend fun sendText(chatId: Long, text: String, replyTo: Int?, markup: JSONObject?, parseMode: String?): Result<Unit> = attempt {
        val s = cur()
        val r = s.api.call(
            "sendMessage",
            mapOf(
                "chat_id" to chatId, "text" to text,
                "parse_mode" to parseMode?.ifEmpty { null },
                "reply_parameters" to replyParams(replyTo), "reply_markup" to markup,
            ),
        )
        ingest(s, r)
    }

    suspend fun sendMedia(
        chatId: Long, kind: String, file: UploadFile, caption: String,
        replyTo: Int?, parseMode: String?, duration: Int? = null,
    ): Result<Unit> = attempt {
        val s = cur()
        val (method, field) = when (kind) {
            "photo" -> "sendPhoto" to "photo"
            "video" -> "sendVideo" to "video"
            "audio" -> "sendAudio" to "audio"
            "voice" -> "sendVoice" to "voice"
            "animation" -> "sendAnimation" to "animation"
            "video_note" -> "sendVideoNote" to "video_note"
            "sticker" -> "sendSticker" to "sticker"
            else -> "sendDocument" to "document"
        }
        val params = mutableMapOf<String, Any?>("chat_id" to chatId, "reply_parameters" to replyParams(replyTo)?.toString())
        if (kind != "sticker" && kind != "video_note") {
            params["caption"] = caption.ifBlank { null }
            params["parse_mode"] = if (caption.isBlank()) null else parseMode?.ifEmpty { null }
        }
        if (duration != null) params["duration"] = duration
        ingest(s, s.api.call(method, params, mapOf(field to file)))
    }

    suspend fun sendLocation(chatId: Long, lat: Double, lon: Double, replyTo: Int?): Result<Unit> = attempt {
        val s = cur()
        ingest(
            s,
            s.api.call(
                "sendLocation",
                mapOf("chat_id" to chatId, "latitude" to lat, "longitude" to lon, "reply_parameters" to replyParams(replyTo)),
            ),
        )
    }

    suspend fun sendContact(chatId: Long, phone: String, first: String, last: String): Result<Unit> = attempt {
        val s = cur()
        ingest(
            s,
            s.api.call(
                "sendContact",
                mapOf("chat_id" to chatId, "phone_number" to phone, "first_name" to first, "last_name" to last.ifBlank { null }),
            ),
        )
    }

    suspend fun sendPoll(chatId: Long, question: String, options: List<String>, anonymous: Boolean, multiple: Boolean): Result<Unit> = attempt {
        val s = cur()
        val opts = JSONArray().also { a -> options.forEach { a.put(JSONObject().put("text", it)) } }
        ingest(
            s,
            s.api.call(
                "sendPoll",
                mapOf(
                    "chat_id" to chatId, "question" to question, "options" to opts,
                    "is_anonymous" to anonymous, "allows_multiple_answers" to multiple,
                ),
            ),
        )
    }

    suspend fun sendDice(chatId: Long, emoji: String): Result<Unit> = attempt {
        val s = cur()
        ingest(s, s.api.call("sendDice", mapOf("chat_id" to chatId, "emoji" to emoji)))
    }

    suspend fun editMessage(chatId: Long, m: Msg, text: String, parseMode: String?): Result<Unit> = attempt {
        val s = cur()
        val textual = m.kind == "text"
        val markup = m.keyboard?.let { JSONObject().put("inline_keyboard", JSONArray(it)) }
        val params = mapOf(
            "chat_id" to chatId, "message_id" to m.id,
            (if (textual) "text" else "caption") to text,
            "parse_mode" to parseMode?.ifEmpty { null },
            "reply_markup" to markup,
        )
        ingest(s, s.api.call(if (textual) "editMessageText" else "editMessageCaption", params), edited = true)
    }

    suspend fun deleteMessage(chatId: Long, id: Int, forEveryone: Boolean): Result<Unit> = attempt {
        val s = cur()
        if (forEveryone) s.api.call("deleteMessage", mapOf("chat_id" to chatId, "message_id" to id))
        s.store.removeMessage(chatId, id)
    }

    suspend fun forward(fromChat: Long, msgId: Int, toChat: Long): Result<Unit> = attempt {
        val s = cur()
        ingest(s, s.api.call("forwardMessage", mapOf("chat_id" to toChat, "from_chat_id" to fromChat, "message_id" to msgId)))
    }

    suspend fun stopPoll(chatId: Long, msgId: Int): Result<Unit> = attempt {
        val s = cur()
        val poll = s.api.callObj("stopPoll", mapOf("chat_id" to chatId, "message_id" to msgId))
        s.store.updatePoll(poll)
    }

    fun typing(chatId: Long) {
        val s = session.value ?: return
        scope.launch {
            try {
                s.api.call("sendChatAction", mapOf("chat_id" to chatId, "action" to "typing"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // ignored
            }
        }
    }

    /** Used by the notification inline-reply action. */
    suspend fun replyFromNotification(accId: Long, chatId: Long, text: String) {
        val s = session.value
        if (s != null && s.account.id == accId) {
            sendText(chatId, text, null, null, null)
        } else {
            accounts.list().firstOrNull { it.id == accId }?.let {
                attempt { BotApi(it.token).call("sendMessage", mapOf("chat_id" to chatId, "text" to text)) }
            }
        }
    }

    suspend fun broadcast(
        chatIds: List<Long>, text: String, parseMode: String?,
        onProgress: (done: Int, failed: Int) -> Unit,
    ) {
        val s = cur()
        var done = 0
        var failed = 0
        for (id in chatIds) {
            var ok = false
            for (tryNo in 0..1) {
                try {
                    ingest(
                        s,
                        s.api.call("sendMessage", mapOf("chat_id" to id, "text" to text, "parse_mode" to parseMode?.ifEmpty { null })),
                    )
                    ok = true
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: TgException) {
                    if (e.code == 429 && tryNo == 0) delay((e.retryAfter + 1) * 1000L) else break
                } catch (e: Exception) {
                    break
                }
            }
            done++
            if (!ok) failed++
            onProgress(done, failed)
            delay(60) // stay well below 30 msg/s
        }
    }

    // ---------------------------------------------------------------- chats

    suspend fun openChat(input: String): Result<Long> = attempt {
        val s = cur()
        val t = input.trim().removePrefix("https://t.me/").removePrefix("http://t.me/").removePrefix("t.me/")
        val id: Any = t.toLongOrNull() ?: (if (t.startsWith("@")) t else "@$t")
        val chat = parseChat(s.api.callObj("getChat", mapOf("chat_id" to id)))
        s.store.upsertChat(chat)
        chat.id
    }

    private fun perms(allow: Boolean) = JSONObject().apply {
        listOf(
            "can_send_messages", "can_send_audios", "can_send_documents", "can_send_photos", "can_send_videos",
            "can_send_video_notes", "can_send_voice_notes", "can_send_polls", "can_send_other_messages",
            "can_add_web_page_previews",
        ).forEach { put(it, allow) }
    }

    /** action: ban, ban_revoke, unban, mute_1h, mute_1d, mute_forever, unmute, promote, demote */
    suspend fun userAction(chatId: Long, userId: Long, action: String): Result<Any?> = attempt {
        val api = cur().api
        val base: Map<String, Any?> = mapOf("chat_id" to chatId, "user_id" to userId)
        val now = System.currentTimeMillis() / 1000
        when (action) {
            "ban" -> api.call("banChatMember", base)
            "ban_revoke" -> api.call("banChatMember", base + ("revoke_messages" to true))
            "unban" -> api.call("unbanChatMember", base + ("only_if_banned" to true))
            "mute_1h" -> api.call("restrictChatMember", base + mapOf("permissions" to perms(false), "until_date" to now + 3600))
            "mute_1d" -> api.call("restrictChatMember", base + mapOf("permissions" to perms(false), "until_date" to now + 86400))
            "mute_forever" -> api.call("restrictChatMember", base + mapOf("permissions" to perms(false)))
            "unmute" -> api.call("restrictChatMember", base + mapOf("permissions" to perms(true)))
            "promote", "demote" -> {
                val on = action == "promote"
                api.call(
                    "promoteChatMember",
                    base + mapOf(
                        "can_manage_chat" to on, "can_delete_messages" to on, "can_restrict_members" to on,
                        "can_pin_messages" to on, "can_invite_users" to on, "can_change_info" to on,
                        "can_manage_video_chats" to on,
                    ),
                )
            }
            else -> error("Unknown action $action")
        }
    }

    // ---------------------------------------------------------------- files

    suspend fun fileUrl(fileId: String): String? {
        urlCache[fileId]?.let { return it }
        val s = session.value ?: return null
        return try {
            val p = s.api.callObj("getFile", mapOf("file_id" to fileId)).optString("file_path")
            if (p.isEmpty()) null else s.api.fileUrl(p).also { urlCache[fileId] = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    suspend fun download(fileId: String, name: String?): Result<File> = attempt {
        val s = cur()
        val path = s.api.callObj("getFile", mapOf("file_id" to fileId)).getString("file_path")
        val dir = File(ctx.cacheDir, "dl").also { it.mkdirs() }
        val clean = (name ?: path.substringAfterLast('/')).replace(Regex("[^A-Za-z0-9._ -]"), "_")
        val dest = File(dir, "${fileId.takeLast(8)}_$clean")
        if (!dest.exists() || dest.length() == 0L) s.api.downloadTo(path, dest)
        dest
    }
}
