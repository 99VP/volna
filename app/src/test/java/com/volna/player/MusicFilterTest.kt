package com.volna.player

import com.volna.player.data.MusicFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicFilterTest {

    @Test
    fun podcastsAndLongVideosAreRejected() {
        assertFalse(MusicFilter.isSong("Подкаст #12: как всё устроено", 3600))
        assertFalse(MusicFilter.isSong("The Sacred Life Podcast", 3215))
        assertFalse(MusicFilter.isSong("Интервью 3", 101))
        assertFalse(MusicFilter.isSong("Lo-Fi Mix 2026", 259))
        assertFalse(MusicFilter.isSong("Artist - Song", 3600))
        assertTrue(MusicFilter.isSong("Artist - Song", 210))
        assertTrue(MusicFilter.isSong("Моя песня", 180))
        assertTrue(MusicFilter.isSong("Сказка", 357))
        assertTrue(MusicFilter.isSong("Heart Shaped Box (2013 Mix)", 279))
    }

    @Test
    fun cautiousDedupeKeepsLiveAndRemix() {
        val base = "Artist - Song (Official Video)"
        assertTrue(MusicFilter.sameSongCautious(base, "Artist - Topic", "Song", "Artist"))
        assertTrue(MusicFilter.sameSongCautious("Song (Lyrics)", "Artist", "Song", "ArtistVEVO"))
        assertFalse(MusicFilter.sameSongCautious("Song (Live)", "Artist", "Song", "Artist"))
        assertFalse(MusicFilter.sameSongCautious("Song (Remix)", "Artist", "Song", "Artist"))
        assertFalse(MusicFilter.sameSongCautious("Song", "Artist", "Another Song", "Artist"))
    }

    @Test
    fun artistPrefixAndTopicAreNormalized() {
        assertEquals("artist|song", MusicFilter.dedupeKey("Artist - Song", "Artist - Topic"))
        assertEquals("artist|song", MusicFilter.dedupeKey("Song (Official Video)", "ArtistVEVO"))
        assertEquals("artist|song live", MusicFilter.dedupeKey("Song (Live)", "Artist"))
    }
}
