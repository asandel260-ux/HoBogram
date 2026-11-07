package com.botgram.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Local database for one bot. The Bot API has no "list my chats" or "fetch history" call,
 * so everything the bot has *received or sent* is kept here.
 */
class Store(private val file: File, private val scope: CoroutineScope) {

    companion object {
        const val MAX_PER_CHAT = 1000
    }

    val chats = MutableStateFlow<List<Chat>>(emptyList())
    val messages = MutableStateFlow<Map<Long, List<Msg>>>(emptyMap())
    val joinRequests = MutableStateFlow<List<JoinReq>>(emptyList())

    @Volatile
    var offset: Long = 0

    private var localId = 0
    private var saveJob: Job? = null
    private val lock = Any()

    fun load() {
        if (!file.exists()) return
        try {
            val root = JSONObject(file.readText())
            offset = root.optLong("offset")
            val cs = root.optJSONArray("chats") ?: JSONArray()
            chats.value = cs.objects().map { chatFromJson(it) }
            val ms = root.optJSONObject("messages") ?: JSONObject()
            val map = HashMap<Long, List<Msg>>()
            for (k in ms.keys()) {
                map[k.toLong()] = ms.getJSONArray(k).objects().map { msgFromJson(it) }
            }
            map.values.forEach { l -> l.forEach { if (it.id < localId) localId = it.id } }
            messages.value = map
            joinRequests.value = (root.optJSONArray("joins") ?: JSONArray()).objects().map { joinFromJson(it) }
        } catch (e: Exception) {
            // corrupted store: start fresh
        }
    }

    fun nextLocalId(): Int = synchronized(lock) { --localId }

    @Synchronized
    fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch(Dispatchers.IO) {
            delay(700)
            writeNow()
        }
    }

    private fun writeNow() {
        try {
            val root = JSONObject()
            root.put("offset", offset)
            root.put("chats", JSONArray().also { a -> chats.value.forEach { a.put(it.toJson()) } })
            val ms = JSONObject()
            messages.value.forEach { (k, v) ->
                ms.put(k.toString(), JSONArray().also { a -> v.forEach { a.put(it.toJson()) } })
            }
            root.put("messages", ms)
            root.put("joins", JSONArray().also { a -> joinRequests.value.forEach { a.put(it.toJson()) } })
            val tmp = File(file.path + ".tmp")
            tmp.writeText(root.toString())
            tmp.renameTo(file)
        } catch (e: Exception) {
            // ignore: next save will retry
        }
    }

    fun delete() {
        saveJob?.cancel()
        file.delete()
    }

    fun upsertChat(c: Chat) = synchronized(lock) {
        val old = chats.value.firstOrNull { it.id == c.id }
        val merged = if (old == null) c else old.copy(
            title = c.title, type = c.type, username = c.username ?: old.username,
        )
        chats.value = chats.value.filter { it.id != c.id } + merged
        scheduleSave()
    }

    fun putMessage(chat: Chat, m: Msg, unread: Boolean) = synchronized(lock) {
        val old = chats.value.firstOrNull { it.id == chat.id }
        val base = old?.copy(title = chat.title, type = chat.type, username = chat.username ?: old.username, left = chat.left)
            ?: chat
        val list = messages.value[chat.id].orEmpty()
        val idx = list.indexOfFirst { it.id == m.id }
        val newList = if (idx >= 0) {
            list.toMutableList().also { it[idx] = m }
        } else {
            (list + m).sortedWith(compareBy<Msg>({ it.date }, { it.id })).takeLast(MAX_PER_CHAT)
        }
        messages.value = messages.value + (chat.id to newList)
        val latest = newList.lastOrNull()?.id == m.id
        val updated = base.copy(
            lastText = if (latest) m.preview() else base.lastText,
            lastDate = if (latest) m.date else base.lastDate,
            unread = base.unread + if (unread && idx < 0) 1 else 0,
        )
        chats.value = chats.value.filter { it.id != chat.id } + updated
        scheduleSave()
    }

    fun markRead(chatId: Long) = synchronized(lock) {
        val c = chats.value.firstOrNull { it.id == chatId } ?: return@synchronized
        if (c.unread == 0) return@synchronized
        chats.value = chats.value.map { if (it.id == chatId) it.copy(unread = 0) else it }
        scheduleSave()
    }

    fun setPinned(chatId: Long, pinned: Boolean) = synchronized(lock) {
        chats.value = chats.value.map { if (it.id == chatId) it.copy(pinned = pinned) else it }
        scheduleSave()
    }

    fun markLeft(chatId: Long, left: Boolean) = synchronized(lock) {
        chats.value = chats.value.map { if (it.id == chatId) it.copy(left = left) else it }
        scheduleSave()
    }

    fun removeMessage(chatId: Long, id: Int) = synchronized(lock) {
        val list = messages.value[chatId] ?: return@synchronized
        val nl = list.filter { it.id != id }
        messages.value = messages.value + (chatId to nl)
        val last = nl.lastOrNull()
        chats.value = chats.value.map {
            if (it.id == chatId) it.copy(lastText = last?.preview().orEmpty(), lastDate = last?.date ?: it.lastDate) else it
        }
        scheduleSave()
    }

    fun clearMessages(chatId: Long) = synchronized(lock) {
        messages.value = messages.value - chatId
        chats.value = chats.value.map { if (it.id == chatId) it.copy(lastText = "", unread = 0) else it }
        scheduleSave()
    }

    fun removeChat(chatId: Long) = synchronized(lock) {
        messages.value = messages.value - chatId
        chats.value = chats.value.filter { it.id != chatId }
        scheduleSave()
    }

    fun updatePoll(poll: JSONObject) = synchronized(lock) {
        val pid = poll.optString("id")
        var changed = false
        val newMap = messages.value.mapValues { (_, list) ->
            list.map { m ->
                if (m.kind == "poll" && m.extra != null && runCatching { JSONObject(m.extra).optString("id") }.getOrNull() == pid) {
                    changed = true
                    m.copy(extra = poll.toString())
                } else m
            }
        }
        if (changed) {
            messages.value = newMap
            scheduleSave()
        }
    }

    fun addJoin(r: JoinReq) = synchronized(lock) {
        joinRequests.value = joinRequests.value.filterNot { it.chatId == r.chatId && it.userId == r.userId } + r
        scheduleSave()
    }

    fun removeJoin(chatId: Long, userId: Long) = synchronized(lock) {
        joinRequests.value = joinRequests.value.filterNot { it.chatId == chatId && it.userId == userId }
        scheduleSave()
    }

    /** Distinct non-bot-self users the bot has seen in a chat (id -> display name). */
    fun usersIn(chatId: Long): Map<Long, String> =
        messages.value[chatId].orEmpty()
            .filter { !it.out && it.fromId > 0 }
            .associate { it.fromId to it.fromName }
}
