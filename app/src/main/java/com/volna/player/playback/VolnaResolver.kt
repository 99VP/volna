package com.volna.player.playback

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import com.volna.player.data.OnlineRepository
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Прячет от ExoPlayer момент извлечения прямой ссылки на аудио.
 *
 * Элементы очереди для интернет-треков хранят ссылку на страницу в схеме
 * `volna://resolve?url=...`. Когда загрузчик ExoPlayer открывает такой элемент
 * (в фоновом потоке), мы через NewPipeExtractor получаем прямой поток и
 * подменяем адрес. Локальные файлы (content://) и прямые ссылки проходят как есть.
 */
class VolnaResolver : ResolvingDataSource.Resolver {

    private data class Entry(val url: String, val ts: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val uri = dataSpec.uri
        if (uri.scheme != SCHEME) return dataSpec
        val page = uri.getQueryParameter("url") ?: return dataSpec
        val title = uri.getQueryParameter("title")
        val direct = cached(page) ?: resolve(page, title)
        return dataSpec.withUri(Uri.parse(direct))
    }

    private fun cached(page: String): String? {
        val entry = cache[page] ?: return null
        if (System.currentTimeMillis() - entry.ts > 10 * 60_000L) {
            cache.remove(page)
            return null
        }
        return entry.url
    }

    private fun resolve(page: String, title: String?): String {
        val direct = runCatching {
            runBlocking { OnlineRepository.resolve(page) }.url
        }.getOrNull()
        if (!direct.isNullOrBlank()) {
            cache[page] = Entry(direct, System.currentTimeMillis())
            return direct
        }

        // Основной источник не отдал поток - пробуем найти этот трек на SoundCloud.
        if (!title.isNullOrBlank()) {
            val alt = runCatching {
                runBlocking { OnlineRepository.resolveFallback(title) }
            }.getOrNull()
            if (!alt.isNullOrBlank()) {
                cache[page] = Entry(alt, System.currentTimeMillis())
                return alt
            }
        }
        throw IOException("Не удалось получить аудиопоток")
    }

    companion object {
        const val SCHEME = "volna"
    }
}
