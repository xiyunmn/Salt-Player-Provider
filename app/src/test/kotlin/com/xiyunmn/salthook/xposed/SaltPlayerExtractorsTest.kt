package com.xiyunmn.salthook.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SaltPlayerExtractorsTest {
    @Test
    fun preservesPureSpaceLyricWords() {
        val document = FakeLyricsDocument(
            "raw",
            listOf(
                FakeLine(
                    1_000L,
                    3_000L,
                    listOf(
                        FakeWord(1_000L, 1_500L, "A"),
                        FakeWord(1_500L, 1_500L, " "),
                        FakeWord(1_500L, 3_000L, "B"),
                    ),
                    "Translated",
                    "A B",
                ),
            ),
        )

        val lyrics = SaltPlayerExtractors.readLyrics(document, "song-1")

        val line = lyrics!!.lines!![0]
        assertEquals("A B", line.text)
        assertEquals("Translated", line.translation)
        assertEquals(listOf("A", " ", "B"), line.words!!.map { it.text })
        assertTrue(line.words!![1].startMs == line.words!![1].endMs)
    }

    private class FakeLyricsDocument(
        @JvmField val source: String,
        @JvmField val lines: List<FakeLine>,
    )

    private class FakeLine(
        @JvmField val start: Long,
        @JvmField val end: Long,
        @JvmField val words: List<FakeWord>,
        @JvmField val translation: String,
        @JvmField val text: String,
    )

    private class FakeWord(
        @JvmField val start: Long,
        @JvmField val end: Long,
        @JvmField val text: String,
    )
}
