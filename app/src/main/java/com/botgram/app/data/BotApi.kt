package com.botgram.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TgException(val code: Int, message: String, val retryAfter: Int = 0) : Exception(message)

class UploadFile(val name: String, val mime: String, val bytes: ByteArray)

/** Thin client over https://core.telegram.org/bots/api — every method is reachable through [call]. */
class BotApi(val token: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(75, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    fun fileUrl(path: String) = "https://api.telegram.org/file/bot$token/$path"

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response)
            }
        })
    }

    suspend fun call(
        method: String,
        params: Map<String, Any?> = emptyMap(),
        files: Map<String, UploadFile> = emptyMap(),
    ): Any? {
        val body: RequestBody = if (files.isEmpty()) {
            val j = JSONObject()
            params.forEach { (k, v) -> if (v != null) j.put(k, JSONObject.wrap(v)) }
            j.toString().toRequestBody("application/json".toMediaType())
        } else {
            val mb = MultipartBody.Builder().setType(MultipartBody.FORM)
            params.forEach { (k, v) -> if (v != null) mb.addFormDataPart(k, v.toString()) }
            files.forEach { (k, f) ->
                mb.addFormDataPart(k, f.name, f.bytes.toRequestBody(f.mime.toMediaType()))
            }
            mb.build()
        }
        val req = Request.Builder().url("https://api.telegram.org/bot$token/$method").post(body).build()
        return withContext(Dispatchers.IO) {
            client.newCall(req).await().use { resp ->
                val text = resp.body?.string().orEmpty()
                val json = try {
                    JSONObject(text)
                } catch (e: Exception) {
                    throw TgException(resp.code, "HTTP ${resp.code}")
                }
                if (!json.optBoolean("ok")) {
                    throw TgException(
                        json.optInt("error_code", resp.code),
                        json.optString("description", "Unknown error"),
                        json.optJSONObject("parameters")?.optInt("retry_after") ?: 0,
                    )
                }
                json.opt("result")
            }
        }
    }

    suspend fun callObj(method: String, params: Map<String, Any?> = emptyMap()): JSONObject =
        call(method, params) as JSONObject

    suspend fun callArr(method: String, params: Map<String, Any?> = emptyMap()): JSONArray =
        call(method, params) as JSONArray

    suspend fun downloadTo(path: String, dest: File) {
        val req = Request.Builder().url(fileUrl(path)).build()
        withContext(Dispatchers.IO) {
            client.newCall(req).await().use { resp ->
                if (!resp.isSuccessful) throw TgException(resp.code, "Download failed (HTTP ${resp.code})")
                val tmp = File(dest.path + ".part")
                resp.body!!.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
                tmp.renameTo(dest)
            }
        }
    }
}
