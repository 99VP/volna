package com.volna.player.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Playlist(
    val id: String,
    var name: String,
    val tracks: MutableList<Track> = mutableListOf(),
)

/** Локальное хранилище избранного и плейлистов (SharedPreferences + JSON). */
class LibraryStore(context: Context) {

    private val prefs = context.getSharedPreferences("volna_library", Context.MODE_PRIVATE)

    // ---- Избранное -------------------------------------------------------

    fun favorites(): List<Track> = parseTracks(prefs.getString(KEY_FAV, null))

    fun isFavorite(track: Track): Boolean = favorites().any { sameTrack(it, track) }

    fun toggleFavorite(track: Track): List<Track> {
        val list = favorites().toMutableList()
        val index = list.indexOfFirst { sameTrack(it, track) }
        // новые - наверх
        if (index >= 0) list.removeAt(index) else list.add(0, track)
        prefs.edit().putString(KEY_FAV, tracksToJson(list).toString()).apply()
        return list
    }

    // ---- Плейлисты -------------------------------------------------------

    fun playlists(): List<Playlist> {
        val raw = prefs.getString(KEY_PL, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                Playlist(
                    id = obj.optString("id"),
                    name = obj.optString("name"),
                    tracks = parseTracks(obj.optJSONArray("tracks")?.toString()).toMutableList(),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun savePlaylists(list: List<Playlist>) {
        val array = JSONArray()
        for (p in list) {
            array.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("tracks", tracksToJson(p.tracks))
            })
        }
        prefs.edit().putString(KEY_PL, array.toString()).apply()
    }

    fun createPlaylist(name: String): List<Playlist> {
        val list = playlists().toMutableList()
        list.add(
            Playlist(
                id = "pl_" + System.currentTimeMillis(),
                name = name.trim().ifBlank { "Плейлист" },
            ),
        )
        savePlaylists(list)
        return list
    }

    fun deletePlaylist(id: String): List<Playlist> {
        val list = playlists().toMutableList()
        list.removeAll { it.id == id }
        savePlaylists(list)
        return list
    }

    fun addToPlaylist(id: String, track: Track): List<Playlist> {
        val list = playlists().toMutableList()
        val playlist = list.firstOrNull { it.id == id } ?: return list
        if (playlist.tracks.none { sameTrack(it, track) }) playlist.tracks.add(track)
        savePlaylists(list)
        return list
    }

    fun removeFromPlaylist(id: String, track: Track): List<Playlist> {
        val list = playlists().toMutableList()
        list.firstOrNull { it.id == id }?.tracks?.removeAll { sameTrack(it, track) }
        savePlaylists(list)
        return list
    }

    /** Полная очистка избранного и плейлистов. */
    fun clear() {
        prefs.edit().clear().apply()
    }

    // ---- Кэш трендов -----------------------------------------------------

    fun saveTrend(kind: String, tracks: List<Track>) {
        prefs.edit().putString("trend_$kind", tracksToJson(tracks).toString()).apply()
    }

    fun loadTrend(kind: String): List<Track> =
        parseTracks(prefs.getString("trend_$kind", null))

    /** Удалить только кэш трендов (не трогая избранное/историю/плейлисты). */
    fun clearTrends() {
        val keys = prefs.all.keys.filter { it.startsWith("trend_") }
        val editor = prefs.edit()
        for (k in keys) editor.remove(k)
        editor.apply()
    }

    // ---- История прослушиваний ------------------------------------------

    fun history(): List<Track> = parseTracks(prefs.getString(KEY_HIST, null))

    fun addHistory(track: Track): List<Track> {
        val list = history().toMutableList()
        list.removeAll { sameTrack(it, track) }
        list.add(0, track)
        val trimmed = list.take(200)
        prefs.edit().putString(KEY_HIST, tracksToJson(trimmed).toString()).apply()
        return trimmed
    }

    fun clearHistory() {
        prefs.edit().remove(KEY_HIST).apply()
    }

    // ---- Очередь видео для «Просмотра» --------------------------------

    fun videoQueue(): List<Track> = parseTracks(prefs.getString(KEY_VIDEO_QUEUE, null))

    fun saveVideoQueue(tracks: List<Track>) {
        prefs.edit().putString(KEY_VIDEO_QUEUE, tracksToJson(tracks).toString()).apply()
    }

    // ---- Вкус: дизлайки и статистика прослушивания ----------------------

    fun disliked(): Set<String> = prefs.getStringSet("disliked", emptySet()) ?: emptySet()

    fun toggleDislike(sig: String): Set<String> {
        val set = disliked().toMutableSet()
        if (!set.add(sig)) set.remove(sig)
        prefs.edit().putStringSet("disliked", set).apply()
        return set
    }

    /**
     * stats: подпись -> [прослушивания, дослушал, скипнул, лайки, в плейлист].
     * Старые записи (3 поля) читаются как 0 в новых ячейках.
     */
    fun stats(): Map<String, IntArray> {
        val raw = prefs.getString("stats", null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            val out = HashMap<String, IntArray>()
            for (key in obj.keys()) {
                val a = obj.getJSONArray(key)
                out[key] = intArrayOf(
                    a.optInt(0), a.optInt(1), a.optInt(2), a.optInt(3), a.optInt(4),
                )
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** field: 0 - play, 1 - complete, 2 - skip, 3 - like, 4 - added to playlist. */
    fun bump(sig: String, field: Int) {
        if (sig.isBlank() || field !in 0..4) return
        val map = stats().toMutableMap()
        val arr = map[sig] ?: IntArray(5)
        arr[field] = arr[field] + 1
        map[sig] = arr
        val obj = JSONObject()
        for ((k, v) in map) {
            obj.put(k, JSONArray().put(v[0]).put(v[1]).put(v[2]).put(v[3]).put(v[4]))
        }
        prefs.edit().putString("stats", obj.toString()).apply()
    }

    // ---- JSON ------------------------------------------------------------

    private fun parseTracks(raw: String?): List<Track> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                array.optJSONObject(i)?.let(::jsonToTrack)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun tracksToJson(list: List<Track>): JSONArray {
        val array = JSONArray()
        for (t in list) array.put(trackToJson(t))
        return array
    }

    private fun trackToJson(t: Track): JSONObject = JSONObject().apply {
        put("id", t.id)
        put("title", t.title)
        put("artist", t.artist)
        put("album", t.album)
        put("durationMs", t.durationMs)
        put("pageUrl", t.pageUrl ?: "")
        put("localUri", t.localUri ?: "")
        put("artworkUri", t.artworkUri ?: "")
        put("source", t.source.name)
    }

    private fun jsonToTrack(o: JSONObject): Track = Track(
        id = o.optString("id"),
        title = o.optString("title"),
        artist = o.optString("artist"),
        album = o.optString("album"),
        durationMs = o.optLong("durationMs"),
        pageUrl = o.optString("pageUrl").ifBlank { null },
        localUri = o.optString("localUri").ifBlank { null },
        artworkUri = o.optString("artworkUri").ifBlank { null },
        source = runCatching { TrackSource.valueOf(o.optString("source")) }
            .getOrDefault(TrackSource.OTHER),
    )

    companion object {
        private const val KEY_FAV = "favorites"
        private const val KEY_PL = "playlists"
        private const val KEY_HIST = "history"
        private const val KEY_VIDEO_QUEUE = "video_queue"

        fun sameTrack(a: Track, b: Track): Boolean =
            if (a.isLocal || b.isLocal) {
                a.localUri != null && a.localUri == b.localUri
            } else {
                (a.pageUrl ?: a.id) == (b.pageUrl ?: b.id)
            }
    }
}
