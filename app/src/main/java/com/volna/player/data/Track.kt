package com.volna.player.data

enum class TrackSource { LOCAL, YOUTUBE, SOUNDCLOUD, OTHER }

/**
 * Универсальный трек: локальный файл телефона либо интернет-источник
 * (YouTube / SoundCloud / ссылка на другой сервис).
 */
data class Track(
    val id: String,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0L,
    /** URL страницы в интернете (youtube.com/watch..., soundcloud.com/...). */
    val pageUrl: String? = null,
    /** content:// ссылка на локальный файл. */
    val localUri: String? = null,
    /** Обложка: content:// или http(s)://. */
    val artworkUri: String? = null,
    val source: TrackSource = TrackSource.LOCAL,
    /** Прямой аудиопоток (если уже известен). */
    val streamUrl: String? = null,
    val mimeType: String? = null,
) {
    val isLocal: Boolean get() = localUri != null
    val subtitle: String get() = artist.ifBlank { album }
}
