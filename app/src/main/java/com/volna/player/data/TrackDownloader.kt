package com.volna.player.data

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import com.volna.player.util.DoH
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Скачивание аудио трека в память телефона (папка Музыка/Волна) через MediaStore. */
object TrackDownloader {

    suspend fun download(
        context: Context,
        track: Track,
        onProgress: (Float) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        val page = track.pageUrl ?: throw IllegalStateException("Это локальный трек")
        val resolved = runCatching { OnlineRepository.resolve(page) }.getOrNull()
        val directUrl = resolved?.url?.takeIf { it.isNotBlank() }
            ?: OnlineRepository.resolveFallback(track.title)
            ?: throw IOException("Не удалось получить аудиопоток")
        val mime = resolved?.mimeType ?: "audio/mp4"
        val ext = when {
            mime.contains("mp4") || mime.contains("m4a") -> "m4a"
            mime.contains("webm") -> "webm"
            mime.contains("mpeg") -> "mp3"
            mime.contains("ogg") || mime.contains("opus") -> "ogg"
            else -> "m4a"
        }
        val title = track.title.ifBlank { "track" }
        val base = (if (track.artist.isNotBlank()) "${track.artist} - $title" else title)
            .replace(Regex("[\\\\/:*?\"<>|]"), " ")
            .trim()
            .take(120)
            .ifBlank { track.id }
        val fileName = "$base.$ext"

        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Волна")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, values)
            ?: throw IOException("Не удалось создать файл")

        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .dns(DoH.dns)
                .build()
            client.newCall(
                Request.Builder()
                    .url(directUrl)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 10) VolnaPlayer")
                    .build(),
            ).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("Пустой ответ")
                val total = body.contentLength()
                var read = 0L
                resolver.openOutputStream(uri)?.use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            read += n
                            if (total > 0) {
                                onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                } ?: throw IOException("Нет доступа на запись")
                onProgress(1f)
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri.toString()
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }
}
