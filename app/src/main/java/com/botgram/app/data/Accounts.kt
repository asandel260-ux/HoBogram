package com.botgram.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/** Bot tokens live in EncryptedSharedPreferences (Android Keystore backed). */
class Accounts(ctx: Context) {

    private val prefs: SharedPreferences = try {
        val key = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            ctx, "botgram_secure", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        ctx.getSharedPreferences("botgram_secure_fallback", Context.MODE_PRIVATE)
    }

    fun list(): List<Account> {
        val raw = prefs.getString("accounts", null) ?: return emptyList()
        return try {
            JSONArray(raw).objects().map {
                Account(it.getString("token"), it.getLong("id"), it.optString("username"), it.optString("name"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(l: List<Account>) {
        val arr = JSONArray()
        l.forEach {
            arr.put(JSONObject().put("token", it.token).put("id", it.id).put("username", it.username).put("name", it.name))
        }
        prefs.edit().putString("accounts", arr.toString()).apply()
    }

    fun add(a: Account) = save(list().filter { it.id != a.id } + a)

    fun remove(id: Long) = save(list().filter { it.id != id })

    var activeId: Long
        get() = prefs.getLong("active", 0)
        set(v) {
            prefs.edit().putLong("active", v).apply()
        }
}
