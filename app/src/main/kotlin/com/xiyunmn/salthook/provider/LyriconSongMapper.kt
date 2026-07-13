package com.xiyunmn.salthook.provider

import com.xiyunmn.salthook.core.LyricLineSnapshot
import com.xiyunmn.salthook.core.LyricWordSnapshot
import com.xiyunmn.salthook.core.LyricsSnapshot
import com.xiyunmn.salthook.core.SongSnapshot
import io.github.proify.lyricon.lyric.model.LyricMetadata
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import java.lang.Math.max
import java.lang.Math.min

object LyriconSongMapper {
    @JvmStatic
    fun toSong(song: SongSnapshot, lyrics: LyricsSnapshot?): Song {
        val lines = ArrayList<RichLyricLine>()
        if (lyrics != null) {
            val sourceLines = lyrics.lines!!
            for (index in sourceLines.indices) {
                val line = sourceLines[index]
                lines.add(toRichLine(line, nextDistinctBegin(sourceLines, index)))
            }
        }
        return Song(song.id, song.title, song.artist, song.durationMs, LyricMetadata(), lines)
    }

    @JvmStatic
    fun describe(song: Song): String {
        val lines = song.lyrics ?: return "richLyrics=null"
        var wordCount = 0
        var translationWordCount = 0
        var translatedLines = 0
        var shortGaps = 0
        var zeroDurationWords = 0
        var minGap = Long.MAX_VALUE
        var lastEnd = Long.MIN_VALUE
        var first: String? = ""
        var firstTranslation: String? = ""
        for (index in lines.indices) {
            val line = lines[index]
            if (index == 0) {
                first = line.text
                firstTranslation = line.translation
            }
            if (lastEnd != Long.MIN_VALUE) {
                val gap = line.begin - lastEnd
                minGap = min(minGap, gap)
                if (gap in 0L..80L) {
                    shortGaps++
                }
            }
            lastEnd = line.end
            val words = line.words
            if (words != null) {
                wordCount += words.size
                for (word in words) {
                    if (word.end <= word.begin) {
                        zeroDurationWords++
                    }
                }
            }
            val translationWords = line.translationWords
            if (translationWords != null) {
                translationWordCount += translationWords.size
            }
            if (!isBlank(line.translation)) {
                translatedLines++
            }
        }
        return "richLyrics{lines=" + lines.size +
            ",words=" + wordCount +
            ",translationWords=" + translationWordCount +
            ",translatedLines=" + translatedLines +
            ",zeroDurationWords=" + zeroDurationWords +
            ",shortGaps<=80ms=" + shortGaps +
            ",minGap=" + (if (minGap == Long.MAX_VALUE) "n/a" else minGap.toString()) +
            ",first=" + preview(first) +
            ",firstTranslation=" + preview(firstTranslation) +
            "}"
    }

    private fun toRichLine(line: LyricLineSnapshot, nextBegin: Long): RichLyricLine {
        val translation = emptyToNull(line.translation)
        var words = toWords(line.words)
        val begin = normalizedBegin(line, words)
        val end = clampEndToNextBegin(begin, normalizedEnd(line, words, begin), nextBegin)
        val duration = max(1L, end - begin)
        if (words.isEmpty()) {
            words = splitEvenly(nullToEmpty(line.text), begin, end)
        } else {
            words = clipWords(words, begin, end)
            if (words.isEmpty()) {
                words = splitEvenly(nullToEmpty(line.text), begin, end)
            }
        }
        val translationWords = clipWords(toWords(line.translationWords), begin, end)

        return RichLyricLine(
            begin,
            end,
            duration,
            false,
            LyricMetadata(),
            nullToEmpty(line.text),
            words,
            null,
            null,
            translation,
            translationWords,
            null,
        )
    }

    private fun nextDistinctBegin(lines: List<LyricLineSnapshot>, index: Int): Long {
        val current = lines[index]
        val currentBegin = max(0L, current.startMs)
        for (nextIndex in index + 1 until lines.size) {
            val next = lines[nextIndex]
            val nextBegin = max(0L, next.startMs)
            if (nextBegin > currentBegin) {
                return nextBegin
            }
        }
        return -1L
    }

    private fun clampEndToNextBegin(begin: Long, end: Long, nextBegin: Long): Long {
        if (nextBegin > begin && nextBegin < end) {
            return max(begin + 1L, nextBegin)
        }
        return max(begin + 1L, end)
    }

    private fun normalizedBegin(line: LyricLineSnapshot, words: List<LyricWord>): Long {
        var begin = max(0L, line.startMs)
        for (word in words) {
            begin = min(begin, max(0L, word.begin))
        }
        return begin
    }

    private fun normalizedEnd(line: LyricLineSnapshot, words: List<LyricWord>, begin: Long): Long {
        var end = max(begin + 1L, line.endMs)
        for (word in words) {
            end = max(end, word.end)
        }
        return max(begin + 1L, end)
    }

    private fun toWords(snapshots: List<LyricWordSnapshot>?): List<LyricWord> {
        val words = ArrayList<LyricWord>()
        if (snapshots == null) {
            return words
        }
        for (snapshot in snapshots) {
            val begin = max(0L, snapshot.startMs)
            val end = max(begin, snapshot.endMs)
            words.add(LyricWord(begin, end, max(0L, end - begin), nullToEmpty(snapshot.text), LyricMetadata()))
        }
        return words
    }

    private fun clipWords(words: List<LyricWord>, beginLimit: Long, endLimit: Long): List<LyricWord> {
        if (words.isEmpty()) {
            return words
        }
        val clipped = ArrayList<LyricWord>(words.size)
        for (word in words) {
            val sourceBegin = word.begin
            val sourceEnd = max(sourceBegin, word.end)
            if (sourceEnd <= beginLimit || sourceBegin >= endLimit) {
                continue
            }
            val begin = max(beginLimit, sourceBegin)
            val end = min(max(begin + 1L, word.end), endLimit)
            clipped.add(LyricWord(begin, end, max(0L, end - begin), nullToEmpty(word.text), LyricMetadata()))
        }
        return clipped
    }

    private fun splitEvenly(text: String, begin: Long, end: Long): List<LyricWord> {
        val words = ArrayList<LyricWord>()
        val length = text.codePointCount(0, text.length)
        if (length <= 0) {
            return words
        }
        val span = max(1L, end - begin)
        var offset = 0
        for (index in 0 until length) {
            val codePoint = text.codePointAt(offset)
            val word = String(Character.toChars(codePoint))
            val wordBegin = begin + span * index / length
            val wordEnd = begin + span * (index + 1L) / length
            words.add(LyricWord(wordBegin, wordEnd, max(0L, wordEnd - wordBegin), word, LyricMetadata()))
            offset += Character.charCount(codePoint)
        }
        return words
    }

    private fun nullToEmpty(value: String?): String = value ?: ""

    private fun emptyToNull(value: String?): String? {
        return if (value == null || value.isEmpty()) null else value
    }

    private fun preview(value: String?): String {
        if (value == null) {
            return "null"
        }
        val trimmed = value.replace('\n', ' ').replace('\r', ' ').trim()
        return if (trimmed.length <= 36) trimmed else trimmed.substring(0, 36) + "..."
    }

    private fun isBlank(value: String?): Boolean {
        return value == null || value.trim().isEmpty()
    }
}
