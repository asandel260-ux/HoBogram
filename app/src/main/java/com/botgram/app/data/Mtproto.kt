package com.botgram.app.data

import android.os.Build
import android.util.Log
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Receives live updates over MTProto (TDLib), logged in as the bot via its token.
 * Unlike getUpdates this does not conflict with a webhook, so the bot's server keeps working.
 * Everything is converted into the same Msg/Chat models the Bot API path uses; sending still
 * goes through the Bot API.
 */
object Mtproto {
    private const val TAG = "BotGramTd"

    private var client: Client? = null
    private var current: Session? = null
    private val users = ConcurrentHashMap<Long, TdApi.User>()
    private val chats = ConcurrentHashMap<Long, TdApi.Chat>()

    fun start(session: Session, apiId: Int, apiHash: String) {
        stop()
        current = session
        users.clear()
        chats.clear()
        val dir = File(Repo.ctx.filesDir, "td_${session.account.id}").also { it.mkdirs() }
        try {
            Client.execute(TdApi.SetLogVerbosityLevel(1))
        } catch (e: Throwable) {
            Log.w(TAG, "log level", e)
        }
        try {
            client = Client.create(
                { obj -> onUpdate(session, obj, dir, apiId, apiHash) },
                { e -> Log.e(TAG, "update handler", e) },
                { e -> Log.e(TAG, "handler", e) },
            )
        } catch (e: Throwable) {
            Repo.conn.value = Conn("unauthorized", "MTProto library failed to load: ${e.message}")
        }
    }

    fun stop() {
        val c = client
        client = null
        current = null
        try {
            c?.send(TdApi.Close()) { }
        } catch (e: Throwable) {
            // ignore
        }
    }

    fun wipe(accountId: Long) {
        File(Repo.ctx.filesDir, "td_$accountId").deleteRecursively()
    }

    private fun send(f: TdApi.Function<*>, onError: ((TdApi.Error) -> Unit)? = null) {
        client?.send(f) { r -> if (r is TdApi.Error) onError?.invoke(r) }
    }

    // ------------------------------------------------------------------ updates

    private fun onUpdate(s: Session, obj: TdApi.Object, dir: File, apiId: Int, apiHash: String) {
        if (current !== s) return
        try {
            when (obj) {
                is TdApi.UpdateAuthorizationState -> when (obj.authorizationState) {
                    is TdApi.AuthorizationStateWaitTdlibParameters -> send(
                        TdApi.SetTdlibParameters(
                            false, dir.absolutePath, "", ByteArray(0), false, true, false, false,
                            apiId, apiHash, "en", Build.MODEL, Build.VERSION.RELEASE, "1.0",
                        ),
                    ) { Repo.conn.value = Conn("unauthorized", it.message) }
                    is TdApi.AuthorizationStateWaitPhoneNumber -> send(
                        TdApi.CheckAuthenticationBotToken(s.account.token),
                    ) { Repo.conn.value = Conn("unauthorized", it.message) }
                    is TdApi.AuthorizationStateReady -> Repo.conn.value = Conn("online")
                    else -> Unit
                }
                is TdApi.UpdateConnectionState -> when (obj.state) {
                    is TdApi.ConnectionStateReady ->
                        if (Repo.conn.value.kind != "unauthorized") Repo.conn.value = Conn("online")
                    is TdApi.ConnectionStateWaitingForNetwork -> Repo.conn.value = Conn("offline", "waiting for network")
                    else -> if (Repo.conn.value.kind == "online") Repo.conn.value = Conn("connecting")
                }
                is TdApi.UpdateUser -> users[obj.user.id] = obj.user
                is TdApi.UpdateNewChat -> {
                    chats[obj.chat.id] = obj.chat
                    s.store.upsertChat(toChat(obj.chat))
                }
                is TdApi.UpdateChatTitle -> chats[obj.chatId]?.let {
                    it.title = obj.title
                    s.store.upsertChat(toChat(it))
                }
                is TdApi.UpdateNewMessage -> onMessage(s, obj.message, false)
                is TdApi.UpdateMessageSendSucceeded -> onMessage(s, obj.message, false)
                is TdApi.UpdateMessageContent -> onContent(s, obj)
                is TdApi.UpdateMessageEdited -> onEdited(s, obj)
                is TdApi.UpdateDeleteMessages -> if (obj.isPermanent) {
                    obj.messageIds.forEach { s.store.removeMessage(obj.chatId, msgId(it)) }
                }
                is TdApi.UpdatePoll -> s.store.updatePoll(pollJson(obj.poll))
                is TdApi.UpdateNewCallbackQuery -> onCallback(s, obj)
                else -> Unit
            }
        } catch (e: Throwable) {
            Log.e(TAG, "update ${obj.javaClass.simpleName} failed", e)
        }
    }

    // ------------------------------------------------------------------ handlers

    private fun chatFor(id: Long): Chat {
        val c = chats[id]
        if (c != null) return toChat(c)
        send(TdApi.GetChat(id))
        return Chat(id, if (id > 0) "private" else "supergroup", id.toString())
    }

    private fun toChat(c: TdApi.Chat): Chat {
        val type = when (val t = c.type) {
            is TdApi.ChatTypePrivate, is TdApi.ChatTypeSecret -> "private"
            is TdApi.ChatTypeBasicGroup -> "group"
            is TdApi.ChatTypeSupergroup -> if (t.isChannel) "channel" else "supergroup"
            else -> "private"
        }
        return Chat(c.id, type, c.title)
    }

    private fun onMessage(s: Session, m: TdApi.Message, edited: Boolean) {
        if (m.sendingState != null) return // local, not yet on the server
        val msg = convert(s, m)
        Repo.deliver(s, chatFor(m.chatId), msg, edited)
    }

    private fun onContent(s: Session, u: TdApi.UpdateMessageContent) {
        val id = msgId(u.messageId)
        val old = s.store.messages.value[u.chatId]?.firstOrNull { it.id == id } ?: return
        val p = parts(u.newContent)
        s.store.putMessage(
            chatFor(u.chatId),
            old.copy(
                kind = p.kind, text = p.text, entities = p.entities, fileId = p.fileId,
                fileName = p.fileName, extra = p.extra, service = p.service, edited = true,
            ),
            unread = false,
        )
    }

    private fun onEdited(s: Session, u: TdApi.UpdateMessageEdited) {
        val id = msgId(u.messageId)
        val old = s.store.messages.value[u.chatId]?.firstOrNull { it.id == id } ?: return
        s.store.putMessage(
            chatFor(u.chatId),
            old.copy(keyboard = keyboardJson(u.replyMarkup) ?: old.keyboard.takeIf { u.replyMarkup == null }, edited = true),
            unread = false,
        )
    }

    private fun onCallback(s: Session, q: TdApi.UpdateNewCallbackQuery) {
        val data = when (val p = q.payload) {
            is TdApi.CallbackQueryPayloadData -> String(p.data)
            is TdApi.CallbackQueryPayloadGame -> p.gameShortName
            else -> ""
        }
        // Deliberately NOT answered here: with a webhook still active, your server answers it.
        Repo.note(s, chatFor(q.chatId), "🔘 ${userLabel(q.senderUserId)} pressed button: $data", 0, q.senderUserId, userLabel(q.senderUserId))
    }

    // ------------------------------------------------------------------ conversion

    /** TDLib message ids are serverId << 20; the Bot API uses the plain server id. */
    private fun msgId(id: Long): Int = if (id and 0xFFFFFL == 0L) (id shr 20).toInt() else id.toInt()

    private fun userLabel(id: Long): String {
        val u = users[id] ?: run { send(TdApi.GetUser(id)); return id.toString() }
        val n = (u.firstName + " " + u.lastName).trim()
        return n.ifBlank { u.usernames?.activeUsernames?.firstOrNull() ?: id.toString() }
    }

    private class Parts(
        var kind: String = "text", var text: String = "", var entities: String? = null,
        var fileId: String? = null, var fileName: String? = null, var extra: String? = null,
        var service: Boolean = false,
    )

    private fun entitiesJson(list: Array<TdApi.TextEntity>?): String? {
        if (list == null || list.isEmpty()) return null
        val a = JSONArray()
        for (e in list) {
            val o = JSONObject().put("offset", e.offset).put("length", e.length)
            val type = when (val t = e.type) {
                is TdApi.TextEntityTypeBold -> "bold"
                is TdApi.TextEntityTypeItalic -> "italic"
                is TdApi.TextEntityTypeUnderline -> "underline"
                is TdApi.TextEntityTypeStrikethrough -> "strikethrough"
                is TdApi.TextEntityTypeSpoiler -> "spoiler"
                is TdApi.TextEntityTypeCode -> "code"
                is TdApi.TextEntityTypePre, is TdApi.TextEntityTypePreCode -> "pre"
                is TdApi.TextEntityTypeUrl -> "url"
                is TdApi.TextEntityTypeTextUrl -> { o.put("url", t.url); "text_link" }
                is TdApi.TextEntityTypeMention -> "mention"
                is TdApi.TextEntityTypeHashtag -> "hashtag"
                is TdApi.TextEntityTypeCashtag -> "cashtag"
                is TdApi.TextEntityTypeBotCommand -> "bot_command"
                is TdApi.TextEntityTypeEmailAddress -> "email"
                is TdApi.TextEntityTypePhoneNumber -> "phone_number"
                is TdApi.TextEntityTypeMentionName -> "text_mention"
                else -> null
            } ?: continue
            a.put(o.put("type", type))
        }
        return if (a.length() == 0) null else a.toString()
    }

    private fun keyboardJson(m: TdApi.ReplyMarkup?): String? {
        val kb = m as? TdApi.ReplyMarkupInlineKeyboard ?: return null
        val rows = JSONArray()
        for (r in kb.rows) {
            val row = JSONArray()
            for (b in r) {
                val o = JSONObject().put("text", b.text)
                when (val t = b.type) {
                    is TdApi.InlineKeyboardButtonTypeUrl -> o.put("url", t.url)
                    is TdApi.InlineKeyboardButtonTypeCallback -> o.put("callback_data", String(t.data))
                    else -> Unit
                }
                row.put(o)
            }
            rows.put(row)
        }
        return rows.toString()
    }

    private fun pollJson(p: TdApi.Poll): JSONObject {
        val opts = JSONArray()
        for (o in p.options) opts.put(JSONObject().put("text", o.text.text).put("voter_count", o.voterCount))
        return JSONObject()
            .put("id", p.id.toString())
            .put("question", p.question.text)
            .put("options", opts)
            .put("total_voter_count", p.totalVoterCount)
            .put("is_closed", p.isClosed)
            .put("is_anonymous", p.isAnonymous)
            .put("allows_multiple_answers", p.allowsMultipleAnswers)
    }

    private fun parts(c: TdApi.MessageContent): Parts {
        val p = Parts()
        fun caption(f: TdApi.FormattedText?) {
            if (f != null) {
                p.text = f.text
                p.entities = entitiesJson(f.entities)
            }
        }
        fun svc(t: String) {
            p.kind = "service"; p.service = true; p.text = t
        }
        when (c) {
            is TdApi.MessageText -> caption(c.text)
            is TdApi.MessagePhoto -> {
                p.kind = "photo"
                p.fileId = c.photo.sizes.lastOrNull()?.photo?.remote?.id
                caption(c.caption)
            }
            is TdApi.MessageVideo -> {
                p.kind = "video"; p.fileId = c.video.video.remote.id
                p.fileName = c.video.fileName.ifEmpty { null }; caption(c.caption)
            }
            is TdApi.MessageAnimation -> {
                p.kind = "animation"; p.fileId = c.animation.animation.remote.id
                p.fileName = c.animation.fileName.ifEmpty { null }; caption(c.caption)
            }
            is TdApi.MessageDocument -> {
                p.kind = "document"; p.fileId = c.document.document.remote.id
                p.fileName = c.document.fileName.ifEmpty { null }; caption(c.caption)
            }
            is TdApi.MessageAudio -> {
                p.kind = "audio"; p.fileId = c.audio.audio.remote.id
                p.fileName = c.audio.fileName.ifEmpty {
                    listOf(c.audio.performer, c.audio.title).filter { it.isNotEmpty() }.joinToString(" - ")
                }.ifEmpty { null }
                caption(c.caption)
            }
            is TdApi.MessageVoiceNote -> {
                p.kind = "voice"; p.fileId = c.voiceNote.voice.remote.id; caption(c.caption)
            }
            is TdApi.MessageVideoNote -> {
                p.kind = "video_note"; p.fileId = c.videoNote.video.remote.id
            }
            is TdApi.MessageSticker -> {
                p.kind = "sticker"; p.fileId = c.sticker.sticker.remote.id; p.text = c.sticker.emoji
                p.extra = if (c.sticker.format is TdApi.StickerFormatWebp) "static" else null
            }
            is TdApi.MessageLocation -> {
                p.kind = "location"; p.text = "📍 Location"
                p.extra = "${c.location.latitude},${c.location.longitude}"
            }
            is TdApi.MessageVenue -> {
                p.kind = "location"; p.text = "📍 ${c.venue.title}\n${c.venue.address}"
                p.extra = "${c.venue.location.latitude},${c.venue.location.longitude}"
            }
            is TdApi.MessageContact -> {
                p.kind = "contact"
                p.text = "👤 ${c.contact.firstName} ${c.contact.lastName}".trim() + "\n📞 " + c.contact.phoneNumber
            }
            is TdApi.MessagePoll -> {
                p.kind = "poll"; p.text = c.poll.question.text; p.extra = pollJson(c.poll).toString()
            }
            is TdApi.MessageDice -> {
                p.kind = "dice"; p.text = "${c.emoji} ${c.value}"
            }
            is TdApi.MessageBasicGroupChatCreate, is TdApi.MessageSupergroupChatCreate -> svc("👥 Group created")
            is TdApi.MessageChatChangeTitle -> svc("✏️ Title changed to “${c.title}”")
            is TdApi.MessageChatAddMembers -> svc("👋 " + c.memberUserIds.joinToString { userLabel(it) } + " joined")
            is TdApi.MessageChatJoinByLink -> svc("👋 Someone joined via invite link")
            is TdApi.MessageChatDeleteMember -> svc("🚪 ${userLabel(c.userId)} left")
            is TdApi.MessagePinMessage -> svc("📌 A message was pinned")
            else -> svc("(unsupported message type)")
        }
        return p
    }

    private fun convert(s: Session, m: TdApi.Message): Msg {
        val (fromId, fromName) = when (val sid = m.senderId) {
            is TdApi.MessageSenderUser -> sid.userId to userLabel(sid.userId).let { if (it == sid.userId.toString()) "" else it }
            is TdApi.MessageSenderChat -> sid.chatId to (chats[sid.chatId]?.title ?: "")
            else -> 0L to ""
        }
        val p = parts(m.content)
        val chatId = m.chatId
        var replyToId: Int? = null
        var replyPreview: String? = null
        (m.replyTo as? TdApi.MessageReplyToMessage)?.let { r ->
            replyToId = msgId(r.messageId)
            val stored = s.store.messages.value[chatId]?.firstOrNull { it.id == replyToId }
            replyPreview = if (stored != null) {
                (if (stored.fromName.isNotEmpty()) stored.fromName + ": " else "") + stored.preview()
            } else r.content?.let { parts(it).text.take(80) }
        }
        return Msg(
            chatId = chatId,
            id = msgId(m.id),
            fromId = fromId,
            fromName = fromName,
            out = m.isOutgoing || fromId == s.account.id,
            date = m.date * 1000L,
            text = p.text,
            kind = p.kind,
            fileId = p.fileId?.ifEmpty { null },
            fileName = p.fileName,
            replyToId = replyToId,
            replyPreview = replyPreview,
            keyboard = keyboardJson(m.replyMarkup),
            entities = p.entities,
            extra = p.extra,
            edited = m.editDate > 0,
            service = p.service,
        )
    }
}
