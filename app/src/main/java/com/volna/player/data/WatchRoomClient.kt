package com.volna.player.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

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

    private val streamCall = AtomicReference<Call?>(null)

    private fun base(url: String): String = url.trim().trimEnd('/')

    private fun isNtfy(server: String): Boolean =
        server.isBlank() || server.contains("ntfy.sh")

    private fun topic(roomId: String): String = "volna-watch-" + roomId

    private fun publishNtfy(roomId: String, video: String, positionMs: Long, playing: Boolean) {
        val body = JSONObject()
            .put("video", video)
            .put("position", positionMs)
            .put("playing", playing)
            .toString()
            .toRequestBody("text/plain; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("https://ntfy.sh/" + topic(roomId))
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
        }
    }

    private fun readNtfy(roomId: String): WatchRoomState {
        val request = Request.Builder()
            .url("https://ntfy.sh/" + topic(roomId) + "/json?poll=1&since=all")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            var last: WatchRoomState? = null
            for (line in response.body?.string().orEmpty().lineSequence()) {
                val state = parseNtfyLine(line) ?: continue
                last = state
            }
            return last ?: throw IllegalStateException("Комната пуста")
        }
    }

    /** Поток новых сообщений ntfy: без частого polling. */
    suspend fun listenNtfy(roomId: String, onState: (WatchRoomState) -> Unit) =
        withContext(Dispatchers.IO) {
            val streamClient = client.newBuilder()
                .readTimeout(0, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder()
                .url("https://ntfy.sh/" + topic(roomId) + "/json")
                .get()
                .build()
            val call = streamClient.newCall(request)
            streamCall.set(call)
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                    val source = response.body?.source() ?: return@withContext
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        val state = parseNtfyLine(line) ?: continue
                        onState(state)
                    }
                }
            } finally {
                streamCall.compareAndSet(call, null)
            }
        }

    fun stopStream() {
        streamCall.getAndSet(null)?.cancel()
    }

    private fun parseNtfyLine(line: String): WatchRoomState? {
        val message = runCatching { JSONObject(line).optString("message") }.getOrNull().orEmpty()
        if (message.isBlank()) return null
        val json = runCatching { JSONObject(message) }.getOrNull() ?: return null
        val video = json.optString("video")
        if (video.isBlank()) return null
        return WatchRoomState(
            video = video,
            positionMs = json.optLong("position"),
            playing = json.optBoolean("playing"),
            updatedAt = json.optLong("updatedAt"),
        )
    }

    suspend fun create(
        server: String,
        video: String,
        positionMs: Long,
        playing: Boolean,
    ): String = withContext(Dispatchers.IO) {
        if (isNtfy(server)) {
            val id = UUID.randomUUID().toString().replace("-", "").take(8)
            publishNtfy(id, video, positionMs, playing)
            return@withContext id
        }
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
        if (isNtfy(server)) {
            publishNtfy(roomId, video, positionMs, playing)
            return@withContext
        }
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
        if (isNtfy(server)) return@withContext readNtfy(roomId)
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
