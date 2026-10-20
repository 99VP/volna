package com.volna.player.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class WatchRoomState(
    val video: String,
    val positionMs: Long,
    val playing: Boolean,
    val updatedAt: Long,
)

/** Клиент комнат совместного просмотра (watch-server/server.py). */
object WatchRoomClient {

    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private fun base(url: String): String = url.trim().trimEnd('/')

    suspend fun create(
        server: String,
        video: String,
        positionMs: Long,
        playing: Boolean,
    ): String = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("video", video)
            .put("position", positionMs)
            .put("playing", playing)
            .toString()
            .toRequestBody(jsonType)
        val request = Request.Builder()
            .url(base(server) + "/api/rooms")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            JSONObject(response.body?.string().orEmpty()).optString("id")
                .ifBlank { throw IllegalStateException("Нет id комнаты") }
        }
    }

    suspend fun update(
        server: String,
        roomId: String,
        video: String,
        positionMs: Long,
        playing: Boolean,
    ) = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("video", video)
            .put("position", positionMs)
            .put("playing", playing)
            .toString()
            .toRequestBody(jsonType)
        val request = Request.Builder()
            .url(base(server) + "/api/rooms/" + roomId)
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
        }
    }

    suspend fun get(server: String, roomId: String): WatchRoomState = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(base(server) + "/api/rooms/" + roomId)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            val json = JSONObject(response.body?.string().orEmpty())
            WatchRoomState(
                video = json.optString("video"),
                positionMs = json.optLong("position"),
                playing = json.optBoolean("playing"),
                updatedAt = json.optLong("updatedAt"),
            )
        }
    }
}
