package com.volna.player.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.volna.player.data.Track

object TrackMediaItem {

    fun uriFor(track: Track): Uri = when {
        track.localUri != null -> Uri.parse(track.localUri)
        track.pageUrl != null -> Uri.Builder()
            .scheme(VolnaResolver.SCHEME)
            .authority("resolve")
            .appendQueryParameter("url", track.pageUrl)
            .appendQueryParameter("title", track.title)
            .build()
        track.streamUrl != null -> Uri.parse(track.streamUrl)
        else -> Uri.EMPTY
    }

    fun toMediaItem(track: Track): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .apply { track.artworkUri?.let { setArtworkUri(Uri.parse(it)) } }
            .build()
        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(uriFor(track))
            .setMediaMetadata(metadata)
            .build()
    }
}
