package com.volna.player.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Локальная музыка телефона через MediaStore. */
object LocalLibrary {

    private val albumArtBase: Uri = Uri.parse("content://media/external/audio/albumart")

    suspend fun load(context: Context): List<Track> = withContext(Dispatchers.IO) {
        val out = ArrayList<Track>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATE_ADDED,
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        // новые (недавно добавленные) - сверху
        val sort = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, selection, null, sort,
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val artist = cursor.getString(artistCol).orEmpty()
                    val albumId = cursor.getLong(albumIdCol)
                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id,
                    )
                    val artUri = ContentUris.withAppendedId(albumArtBase, albumId).toString()
                    out.add(
                        Track(
                            id = "local:$id",
                            title = cursor.getString(titleCol).orEmpty().ifBlank { "Без названия" },
                            artist = if (artist.equals("<unknown>", true)) "" else artist,
                            album = cursor.getString(albumCol).orEmpty(),
                            durationMs = cursor.getLong(durationCol),
                            localUri = contentUri.toString(),
                            artworkUri = artUri,
                            source = TrackSource.LOCAL,
                        ),
                    )
                }
            }
        }
        out
    }
}
