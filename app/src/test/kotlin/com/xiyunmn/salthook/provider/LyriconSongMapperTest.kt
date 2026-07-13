package com.xiyunmn.salthook.provider

import com.xiyunmn.salthook.core.LyricLineSnapshot
import com.xiyunmn.salthook.core.LyricWordSnapshot
import com.xiyunmn.salthook.core.LyricsSnapshot
import com.xiyunmn.salthook.core.SongSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LyriconSongMapperTest {
    @Test
    fun mapsTranslationToStandardLyriconFields() {
        val song = SongSnapshot("1", "Title", "Artist", 30_000L)
        val lyrics = LyricsSnapshot(
            "1",
            listOf(
                LyricLineSnapshot(
                    1_000L,
                    3_000L,
                    "主歌词",
                    "Translated line",
                    listOf(LyricWordSnapshot(1_000L, 3_000L, "主歌词")),
                    listOf(LyricWordSnapshot(1_000L, 3_000L, "Translated line")),
                ),
            ),
            "test",
        )

        val richLine = LyriconSongMapper.toSong(song, lyrics).lyrics!![0]

        assertEquals("主歌词", richLine.text)
        assertEquals("Translated line", richLine.translation)
        assertEquals(1, richLine.translationWords!!.size)
        assertNull(richLine.secondary)
    }

    @Test
    fun clampsOverlappingLineEndToNextDistinctBegin() {
        val song = SongSnapshot("1", "Title", "Artist", 30_000L)
        val lyrics = LyricsSnapshot(
            "1",
            listOf(
                LyricLineSnapshot(
                    1_000L,
                    5_000L,
                    "first",
                    "translation",
                    listOf(LyricWordSnapshot(1_000L, 5_000L, "first")),
                    emptyList(),
                ),
                LyricLineSnapshot(
                    4_000L,
                    6_000L,
                    "second",
                    null,
                    listOf(LyricWordSnapshot(4_000L, 6_000L, "second")),
                    emptyList(),
                ),
            ),
            "test",
        )

        val richLyrics = LyriconSongMapper.toSong(song, lyrics).lyrics!!

        assertEquals(4_000L, richLyrics[0].end)
        assertEquals(3_000L, richLyrics[0].duration)
        assertEquals(4_000L, richLyrics[1].begin)
    }

    @Test
    fun createsFallbackWordsByUnicodeCodePoint() {
        val song = SongSnapshot("1", "Title", "Artist", 30_000L)
        val lyrics = LyricsSnapshot(
            "1",
            listOf(
                LyricLineSnapshot(0L, 3_000L, "A😀B", null, emptyList(), emptyList()),
            ),
            "test",
        )

        val words = LyriconSongMapper.toSong(song, lyrics).lyrics!![0].words!!

        assertEquals(listOf("A", "😀", "B"), words.map { it.text })
        assertEquals(3, words.size)
        assertNotNull(words.first().metadata)
    }
}
