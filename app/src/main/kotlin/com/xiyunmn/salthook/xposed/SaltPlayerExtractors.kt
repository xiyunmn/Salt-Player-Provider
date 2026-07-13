package com.xiyunmn.salthook.xposed

import com.xiyunmn.salthook.core.LyricLineSnapshot
import com.xiyunmn.salthook.core.LyricWordSnapshot
import com.xiyunmn.salthook.core.LyricsSnapshot
import com.xiyunmn.salthook.core.SongSnapshot
import com.xiyunmn.salthook.diagnostics.SaltDiagnostics
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object SaltPlayerExtractors {
    private const val DOCUMENT_LINES_FIELD = "\u0528"
    private const val LINE_START_FIELD = "\u037f"
    private const val LINE_END_FIELD = "\u0528"
    private const val LINE_WORDS_FIELD = "\u0529"
    private const val LINE_TRANSLATION_FIELD = "\u052a"
    private const val LINE_TEXT_FIELD = "\u052b"
    private const val WORD_START_FIELD = "\u037f"
    private const val WORD_END_FIELD = "\u0528"
    private const val WORD_TEXT_FIELD = "\u0529"
    private const val INTRO_CREDIT_MAX_START_MS = 12_000L
    private const val INTRO_CREDIT_LINE_MAX_DURATION_MS = 350L
    private const val INTRO_CREDIT_CLUSTER_MAX_SPAN_MS = 1_500L
    private const val INTRO_CREDIT_MAX_GAP_MS = 120L
    private const val INTRO_CREDIT_MIN_CLUSTER_LINES = 2
    private const val TIMELINE_DIAGNOSTIC_SAMPLE_LIMIT = 20

    private val fieldCache = ConcurrentHashMap<String, Field>()
    private val documentClassCache = ConcurrentHashMap<Class<*>, Boolean>()
    private val documentLinesCache = ConcurrentHashMap<Class<*>, Field>()
    private val lineSchemaCache = ConcurrentHashMap<Class<*>, LineSchema>()
    private val wordSchemaCache = ConcurrentHashMap<Class<*>, WordSchema>()

    private val creditLabels = arrayOf(
        "词", "曲", "词曲", "作词", "作曲", "填词", "编曲", "制作人", "制作",
        "监制", "出品", "发行", "企划", "统筹", "演唱", "原唱", "歌手",
        "和声", "合声", "和音", "配唱", "录音", "混音", "母带", "吉他",
        "贝斯", "鼓", "弦乐", "钢琴", "键盘", "编程",
        "lyrics", "lyric", "lyricist", "composer", "arranger", "producer",
        "vocal", "vocals", "singer", "guitar", "bass", "drums", "piano",
        "keyboard", "strings", "recording", "mixing", "mastering", "op", "sp",
    )

    fun isLikelyLyricsDocumentClass(type: Class<*>?): Boolean {
        if (type == null) {
            return false
        }
        val cached = documentClassCache[type]
        if (cached != null) {
            return cached
        }

        val result = computeLikelyLyricsDocumentClass(type)
        documentClassCache[type] = result
        if (result && SaltDiagnostics.enabled()) {
            SaltDiagnostics.trace("extractor.schema", "lyricsDocumentClassCandidate=" + type.name)
        }
        return result
    }

    private fun computeLikelyLyricsDocumentClass(type: Class<*>?): Boolean {
        if (
            type == null ||
            type.isPrimitive ||
            type.isArray ||
            type.isInterface ||
            type.isAnnotation ||
            type.isEnum
        ) {
            return false
        }

        val name = type.name
        if (
            name.startsWith("java.") ||
            name.startsWith("javax.") ||
            name.startsWith("android.") ||
            name.startsWith("kotlin.") ||
            name.startsWith("kotlinx.") ||
            name.startsWith("com.salt.music.data.entry.")
        ) {
            return false
        }

        var hasSourceText = false
        var hasLineContainer = false
        for (field in declaredInstanceFields(type)) {
            val fieldType = field.type
            if (fieldType == String::class.java) {
                hasSourceText = true
            }
            if (fieldType == Object::class.java || java.util.List::class.java.isAssignableFrom(fieldType)) {
                hasLineContainer = true
            }
        }
        return hasSourceText && hasLineContainer && hasStringListConstructor(type)
    }

    fun readSong(songObject: Any?): SongSnapshot? {
        if (songObject == null) {
            SaltDiagnostics.trace("extractor", "readSong null input")
            return null
        }

        val start = SaltDiagnostics.now()
        var id = stringMethod(songObject, "getId")
        val songId = callNoArg(songObject, "getSongId")
        if (songId is Number && songId.toLong() > 0L) {
            id = songId.toLong().toString()
        }
        val title = stringMethod(songObject, "getTitle")
        val artist = stringMethod(songObject, "getArtist")
        val duration = longMethod(songObject, "getDuration")

        if (isBlank(id) && isBlank(title)) {
            if (SaltDiagnostics.enabled()) {
                SaltDiagnostics.trace(
                    "extractor",
                    "readSong rejected class=" + songObject.javaClass.name +
                        " elapsedMs=" + SaltDiagnostics.elapsedMs(start) +
                        " fields=" + describeFields(songObject, 10),
                )
            }
            return null
        }
        val song = SongSnapshot(nullToEmpty(id), nullToEmpty(title), nullToEmpty(artist), kotlin.math.max(0L, duration))
        if (SaltDiagnostics.enabled()) {
            SaltDiagnostics.trace(
                "extractor",
                "readSong ok class=" + songObject.javaClass.name +
                    " elapsedMs=" + SaltDiagnostics.elapsedMs(start) +
                    " id=" + song.id +
                    " title=" + preview(song.title) +
                    " artist=" + preview(song.artist) +
                    " duration=" + song.durationMs,
            )
        }
        return song
    }

    fun readLyrics(lyricObject: Any?, songId: String?): LyricsSnapshot? {
        if (lyricObject == null || isBlank(songId)) {
            SaltDiagnostics.trace(
                "extractor",
                "readLyrics rejected input lyricObject=" +
                    (lyricObject?.javaClass?.name ?: "null") +
                    " songId=" + songId,
            )
            return null
        }
        val start = SaltDiagnostics.now()
        SaltDiagnostics.count("extractor.readLyrics")
        val lyricClass = lyricObject.javaClass.name

        if (SaltDiagnostics.enabled()) {
            val likelyDocument = isLikelyLyricsDocumentClass(lyricObject.javaClass)
            SaltDiagnostics.trace(
                "extractor",
                "readLyrics begin songId=" + songId +
                    " class=" + lyricClass +
                    " likelyDocument=" + likelyDocument +
                    " docFields=" + describeFields(lyricObject, 12),
            )
        }
        val sourceLines = readDocumentLines(lyricObject)
        if (sourceLines == null || sourceLines.isEmpty()) {
            SaltDiagnostics.count("extractor.readLyrics.noLines")
            if (SaltDiagnostics.enabled()) {
                SaltDiagnostics.trace(
                    "extractor",
                    "readLyrics no lines songId=" + songId +
                        " class=" + lyricClass +
                        " elapsedMs=" + SaltDiagnostics.elapsedMs(start) +
                        " docFields=" + describeFields(lyricObject, 16),
                )
            }
            return null
        }

        var lines = ArrayList<LyricLineSnapshot>()
        for (index in sourceLines.indices) {
            val sourceLine = sourceLines[index]
            val line = readLine(sourceLine)
            if (SaltDiagnostics.enabled() && index < 5) {
                SaltDiagnostics.trace(
                    "extractor.line",
                    "line[$index] rawClass=" +
                        (sourceLine?.javaClass?.name ?: "null") +
                        " parsed=" + describeLine(line) +
                        " rawFields=" + describeFields(sourceLine, 12) +
                        " firstWordFields=" + describeFields(findFirstRawWord(sourceLine), 8),
                )
            }
            if (line != null && !isBlank(line.text)) {
                lines.add(line)
            }
        }
        if (lines.isEmpty()) {
            SaltDiagnostics.count("extractor.readLyrics.emptyParsed")
            SaltDiagnostics.trace(
                "extractor",
                "readLyrics parsed empty songId=" + songId +
                    " sourceLines=" + sourceLines.size +
                    " elapsedMs=" + SaltDiagnostics.elapsedMs(start),
            )
            return null
        }
        lines = ArrayList(stabilizeIntroCreditClusters(lines, songId))
        logTimelineDiagnostics(songId, lines)
        val snapshot = LyricsSnapshot(songId, lines, lyricClass)
        if (SaltDiagnostics.enabled()) {
            SaltDiagnostics.trace(
                "extractor",
                "readLyrics ok songId=" + songId +
                    " elapsedMs=" + SaltDiagnostics.elapsedMs(start) +
                    " sourceLines=" + sourceLines.size +
                    " outputLines=" + lines.size +
                    " lineDelta=" + (sourceLines.size - lines.size) +
                    " " + describeLyrics(snapshot),
            )
        }
        return snapshot
    }

    private fun readDocumentLines(source: Any): List<*>? {
        val value = readField(source, DOCUMENT_LINES_FIELD)
        if (value is List<*> && isLikelyLineList(value, true)) {
            return value
        }

        val cached = documentLinesCache[source.javaClass]
        if (cached != null) {
            val cachedValue = readFieldValue(source, cached)
            if (cachedValue is List<*>) {
                return cachedValue
            }
        }

        val inferred = inferDocumentLinesField(source)
        if (inferred != null) {
            documentLinesCache[source.javaClass] = inferred
            val inferredValue = readFieldValue(source, inferred)
            if (inferredValue is List<*>) {
                if (SaltDiagnostics.enabled()) {
                    SaltDiagnostics.trace(
                        "extractor.schema",
                        "document class=" + source.javaClass.name +
                            " linesField=" + inferred.name +
                            " fields=" + describeFields(source, 8),
                    )
                }
                return inferredValue
            }
        }

        if (SaltDiagnostics.enabled()) {
            SaltDiagnostics.trace(
                "extractor",
                "lyrics document missing lines field value=" + describeValue(value) +
                    " fields=" + describeFields(source, 8),
            )
        }
        return null
    }

    private fun readLine(sourceLine: Any?): LyricLineSnapshot? {
        val schema = lineSchemaFor(sourceLine) ?: return null

        var start = readLongField(sourceLine, schema.startField, 0L)
        var end = readLongField(sourceLine, schema.endField, start)
        if (start > end) {
            val temp = start
            start = end
            end = temp
        }

        val words = readSaltWords(readListField(sourceLine, schema.wordsField))
        var text = if (schema.textField == null) "" else readStringField(sourceLine, schema.textField)
        if (isBlank(text)) {
            text = joinWords(words)
        }
        val translation = if (schema.translationField == null) {
            null
        } else {
            emptyToNull(readStringField(sourceLine, schema.translationField))
        }
        return LyricLineSnapshot(start, end, nullToEmpty(text), translation, words, emptyList())
    }

    private fun stabilizeIntroCreditClusters(
        lines: List<LyricLineSnapshot>,
        songId: String?,
    ): List<LyricLineSnapshot> {
        if (lines.size < INTRO_CREDIT_MIN_CLUSTER_LINES) {
            return lines
        }

        val result = ArrayList<LyricLineSnapshot>(lines.size)
        val samples = ArrayList<String>()
        var clusters = 0
        var mergedLines = 0
        var index = 0

        while (index < lines.size) {
            val first = lines[index]
            if (!isShortIntroCreditLine(first)) {
                result.add(first)
                index++
                continue
            }

            var endExclusive = index + 1
            val clusterStart = first.startMs
            var clusterEnd = first.endMs
            while (endExclusive < lines.size) {
                val candidate = lines[endExclusive]
                val gap = candidate.startMs - clusterEnd
                val span = kotlin.math.max(clusterEnd, candidate.endMs) - clusterStart
                if (
                    !isShortIntroCreditLine(candidate) ||
                    gap < 0L ||
                    gap > INTRO_CREDIT_MAX_GAP_MS ||
                    span > INTRO_CREDIT_CLUSTER_MAX_SPAN_MS
                ) {
                    break
                }
                clusterEnd = kotlin.math.max(clusterEnd, candidate.endMs)
                endExclusive++
            }

            val count = endExclusive - index
            if (count < INTRO_CREDIT_MIN_CLUSTER_LINES) {
                result.add(first)
                index++
                continue
            }

            val merged = mergeIntroCreditCluster(lines, index, endExclusive)
            result.add(merged)
            clusters++
            mergedLines += count
            if (samples.size < 6) {
                samples.add(
                    "cluster[" + index + "-" + (endExclusive - 1) + "]" +
                        " span=" + kotlin.math.max(0L, merged.endMs - merged.startMs) +
                        "ms text=" + preview(merged.text) +
                        " source=" + describeCluster(lines, index, endExclusive),
                )
            }
            index = endExclusive
        }

        if (clusters == 0) {
            return lines
        }

        SaltDiagnostics.count("extractor.introCredits.stabilized")
        if (SaltDiagnostics.enabled()) {
            SaltDiagnostics.trace(
                "extractor",
                "stabilizeIntroCredits songId=" + songId +
                    " clusters=" + clusters +
                    " mergedSourceLines=" + mergedLines +
                    " inputLines=" + lines.size +
                    " outputLines=" + result.size +
                    " samples=" + samples,
            )
        }
        return result
    }

    private fun mergeIntroCreditCluster(
        lines: List<LyricLineSnapshot>,
        startIndex: Int,
        endExclusive: Int,
    ): LyricLineSnapshot {
        val first = lines[startIndex]
        val last = lines[endExclusive - 1]
        val begin = kotlin.math.max(0L, first.startMs)
        val end = kotlin.math.max(begin + 1L, last.endMs)
        val text = StringBuilder()
        for (index in startIndex until endExclusive) {
            if (text.isNotEmpty()) {
                text.append(" / ")
            }
            text.append(nullToEmpty(lines[index].text).trim())
        }
        val mergedText = text.toString()
        val words = listOf(LyricWordSnapshot(begin, end, mergedText))
        return LyricLineSnapshot(begin, end, mergedText, null, words, emptyList())
    }

    private fun isShortIntroCreditLine(line: LyricLineSnapshot?): Boolean {
        if (line == null || line.startMs < 0L || line.startMs > INTRO_CREDIT_MAX_START_MS) {
            return false
        }
        val duration = line.endMs - line.startMs
        return duration > 0L &&
            duration <= INTRO_CREDIT_LINE_MAX_DURATION_MS &&
            isBlank(line.translation) &&
            isCreditText(line.text)
    }

    private fun isCreditText(value: String?): Boolean {
        val text = nullToEmpty(value).trim()
        if (text.isEmpty() || text.length > 40) {
            return false
        }
        val separator = creditSeparatorIndex(text)
        if (separator <= 0 || separator > 18) {
            return false
        }
        val label = text.substring(0, separator)
            .trim()
            .replace(" ", "")
            .replace("　", "")
            .lowercase(Locale.ROOT)
        if (label.isEmpty()) {
            return false
        }
        for (creditLabel in creditLabels) {
            if (matchesCreditLabel(label, creditLabel)) {
                return true
            }
        }
        return false
    }

    private fun matchesCreditLabel(label: String, creditLabel: String): Boolean {
        val normalizedCreditLabel = creditLabel.lowercase(Locale.ROOT)
        if (label == normalizedCreditLabel) {
            return true
        }
        return normalizedCreditLabel.length > 1 && label.startsWith(normalizedCreditLabel)
    }

    private fun creditSeparatorIndex(text: String): Int {
        val colon = text.indexOf(':')
        val fullWidthColon = text.indexOf('：')
        val smallColon = text.indexOf('﹕')
        var result = -1
        if (colon >= 0) {
            result = colon
        }
        if (fullWidthColon >= 0 && (result < 0 || fullWidthColon < result)) {
            result = fullWidthColon
        }
        if (smallColon >= 0 && (result < 0 || smallColon < result)) {
            result = smallColon
        }
        return result
    }

    private fun describeCluster(lines: List<LyricLineSnapshot>, startIndex: Int, endExclusive: Int): String {
        val builder = StringBuilder("[")
        for (index in startIndex until endExclusive) {
            if (index > startIndex) {
                builder.append(';')
            }
            val line = lines[index]
            builder.append(index)
                .append(':')
                .append(line.startMs)
                .append('-')
                .append(line.endMs)
                .append('=')
                .append(preview(line.text))
        }
        return builder.append(']').toString()
    }

    private fun readSaltWords(sourceWords: List<*>?): List<LyricWordSnapshot> {
        val words = ArrayList<LyricWordSnapshot>()
        if (sourceWords == null) {
            return words
        }
        for (sourceWord in sourceWords) {
            val word = readSaltWord(sourceWord)
            if (word != null && word.text != null && word.text.isNotEmpty()) {
                words.add(word)
            }
        }
        return words
    }

    private fun readSaltWord(sourceWord: Any?): LyricWordSnapshot? {
        val schema = wordSchemaFor(sourceWord) ?: return null

        var start = readLongField(sourceWord, schema.startField, 0L)
        var end = readLongField(sourceWord, schema.endField, start)
        if (start > end) {
            val temp = start
            start = end
            end = temp
        }
        val text = readStringField(sourceWord, schema.textField)
        return LyricWordSnapshot(start, end, nullToEmpty(text))
    }

    private fun readLongField(source: Any?, field: Field?, fallback: Long): Long {
        val value = readFieldValue(source, field)
        return if (value is Number) value.toLong() else fallback
    }

    private fun readStringField(source: Any?, field: Field?): String {
        val value = readFieldValue(source, field)
        return if (value is String) value else ""
    }

    private fun readListField(source: Any?, field: Field?): List<*>? {
        val value = readFieldValue(source, field)
        return if (value is List<*>) value else null
    }

    private fun inferDocumentLinesField(source: Any): Field? {
        var bestField: Field? = null
        var bestScore = 0
        for (field in declaredInstanceFields(source.javaClass)) {
            val value = readFieldValue(source, field)
            if (value !is List<*>) {
                continue
            }
            val score = scoreLineList(value)
            if (score > bestScore) {
                bestScore = score
                bestField = field
            }
        }
        return if (bestScore > 0) bestField else null
    }

    private fun isLikelyLineList(list: List<*>, allowEmpty: Boolean): Boolean {
        return allowEmpty && list.isEmpty() || scoreLineList(list) > 0
    }

    private fun scoreLineList(list: List<*>): Int {
        var checked = 0
        var parsed = 0
        for (value in list) {
            if (value == null) {
                continue
            }
            checked++
            if (lineSchemaFor(value) != null) {
                parsed++
            }
            if (checked >= 8) {
                break
            }
        }
        return if (parsed == 0) 0 else parsed * 100 + kotlin.math.min(list.size, 99)
    }

    private fun lineSchemaFor(sourceLine: Any?): LineSchema? {
        if (sourceLine == null) {
            return null
        }
        val type = sourceLine.javaClass
        val cached = lineSchemaCache[type]
        if (cached != null) {
            return cached
        }

        var schema = knownLineSchema(sourceLine)
        if (schema == null) {
            schema = inferLineSchema(sourceLine)
        }
        if (schema != null) {
            lineSchemaCache[type] = schema
            if (SaltDiagnostics.enabled()) {
                SaltDiagnostics.trace(
                    "extractor.schema",
                    "line class=" + type.name +
                        " " + schema.describe() +
                        " sample=" + describeFields(sourceLine, 8),
                )
            }
        }
        return schema
    }

    private fun knownLineSchema(sourceLine: Any): LineSchema? {
        val type = sourceLine.javaClass
        val start = fieldOrNull(type, LINE_START_FIELD)
        val end = fieldOrNull(type, LINE_END_FIELD)
        val words = fieldOrNull(type, LINE_WORDS_FIELD)
        val translation = fieldOrNull(type, LINE_TRANSLATION_FIELD)
        val text = fieldOrNull(type, LINE_TEXT_FIELD)
        if (start == null || end == null || words == null || text == null) {
            return null
        }
        if (readFieldValue(sourceLine, words) !is List<*>) {
            return null
        }
        return LineSchema(start, end, words, translation, text)
    }

    private fun inferLineSchema(sourceLine: Any): LineSchema? {
        val numberFields = ArrayList<Field>()
        val listFields = ArrayList<Field>()
        val stringFields = ArrayList<Field>()
        for (field in declaredInstanceFields(sourceLine.javaClass)) {
            val value = readFieldValue(sourceLine, field)
            if (isNumericField(field, value)) {
                numberFields.add(field)
            }
            if (value is List<*>) {
                listFields.add(field)
            }
            if (field.type == String::class.java || value is String) {
                stringFields.add(field)
            }
        }
        if (numberFields.size < 2 || listFields.isEmpty()) {
            return null
        }

        var wordListField: Field? = null
        var bestWordScore = 0
        for (field in listFields) {
            val value = readFieldValue(sourceLine, field)
            val score = if (value is List<*>) scoreWordList(value) else 0
            if (score > bestWordScore) {
                bestWordScore = score
                wordListField = field
            }
        }
        if (wordListField == null) {
            return null
        }

        val sampleWords = readSaltWords(readListField(sourceLine, wordListField))
        val joinedWords = joinWords(sampleWords)
        val textField = chooseMainTextField(sourceLine, stringFields, joinedWords)
        val translationField = chooseTranslationField(stringFields, textField)
        return LineSchema(numberFields[0], numberFields[1], wordListField, translationField, textField)
    }

    private fun chooseMainTextField(
        sourceLine: Any,
        stringFields: List<Field>,
        joinedWords: String,
    ): Field? {
        if (!isBlank(joinedWords)) {
            for (field in stringFields) {
                if (joinedWords == readStringField(sourceLine, field)) {
                    return field
                }
            }
        }
        for (index in stringFields.size - 1 downTo 0) {
            val field = stringFields[index]
            if (!isBlank(readStringField(sourceLine, field))) {
                return field
            }
        }
        return if (stringFields.isEmpty()) null else stringFields[stringFields.size - 1]
    }

    private fun chooseTranslationField(stringFields: List<Field>, textField: Field?): Field? {
        for (field in stringFields) {
            if (field !== textField) {
                return field
            }
        }
        return null
    }

    private fun scoreWordList(list: List<*>): Int {
        var checked = 0
        var parsed = 0
        for (value in list) {
            if (value == null) {
                continue
            }
            checked++
            if (wordSchemaFor(value) != null) {
                parsed++
            }
            if (checked >= 8) {
                break
            }
        }
        return if (parsed == 0) 0 else parsed * 100 + kotlin.math.min(list.size, 99)
    }

    private fun wordSchemaFor(sourceWord: Any?): WordSchema? {
        if (sourceWord == null) {
            return null
        }
        val type = sourceWord.javaClass
        val cached = wordSchemaCache[type]
        if (cached != null) {
            return cached
        }

        var schema = knownWordSchema(sourceWord)
        if (schema == null) {
            schema = inferWordSchema(sourceWord)
        }
        if (schema != null) {
            wordSchemaCache[type] = schema
            if (SaltDiagnostics.enabled()) {
                SaltDiagnostics.trace(
                    "extractor.schema",
                    "word class=" + type.name +
                        " " + schema.describe() +
                        " sample=" + describeFields(sourceWord, 6),
                )
            }
        }
        return schema
    }

    private fun knownWordSchema(sourceWord: Any): WordSchema? {
        val type = sourceWord.javaClass
        val start = fieldOrNull(type, WORD_START_FIELD)
        val end = fieldOrNull(type, WORD_END_FIELD)
        val text = fieldOrNull(type, WORD_TEXT_FIELD)
        if (start == null || end == null || text == null) {
            return null
        }
        if (readFieldValue(sourceWord, text) !is String) {
            return null
        }
        return WordSchema(start, end, text)
    }

    private fun inferWordSchema(sourceWord: Any): WordSchema? {
        val numberFields = ArrayList<Field>()
        val stringFields = ArrayList<Field>()
        for (field in declaredInstanceFields(sourceWord.javaClass)) {
            val value = readFieldValue(sourceWord, field)
            if (isNumericField(field, value)) {
                numberFields.add(field)
            }
            if (field.type == String::class.java || value is String) {
                stringFields.add(field)
            }
        }
        if (numberFields.size < 2 || stringFields.isEmpty()) {
            return null
        }
        return WordSchema(numberFields[0], numberFields[1], stringFields[0])
    }

    private fun readField(source: Any?, fieldName: String): Any? {
        if (source == null) {
            return null
        }
        return try {
            fieldFor(source.javaClass, fieldName).get(source)
        } catch (ignored: Throwable) {
            null
        }
    }

    @Throws(NoSuchFieldException::class)
    private fun fieldFor(type: Class<*>, fieldName: String): Field {
        val key = type.name + '#' + fieldName
        val cached = fieldCache[key]
        if (cached != null) {
            return cached
        }

        var current: Class<*>? = type
        while (current != null && current != Object::class.java) {
            try {
                val field = current.getDeclaredField(fieldName)
                field.isAccessible = true
                val existing = fieldCache.putIfAbsent(key, field)
                return existing ?: field
            } catch (ignored: NoSuchFieldException) {
                current = current.superclass
            }
        }
        throw NoSuchFieldException(key)
    }

    private fun fieldOrNull(type: Class<*>, fieldName: String): Field? {
        return try {
            fieldFor(type, fieldName)
        } catch (ignored: Throwable) {
            null
        }
    }

    private fun readFieldValue(source: Any?, field: Field?): Any? {
        if (source == null || field == null) {
            return null
        }
        return try {
            field.get(source)
        } catch (ignored: Throwable) {
            null
        }
    }

    private fun declaredInstanceFields(type: Class<*>): List<Field> {
        val fields = ArrayList<Field>()
        var current: Class<*>? = type
        while (current != null && current != Object::class.java) {
            for (field in current.declaredFields) {
                if (Modifier.isStatic(field.modifiers)) {
                    continue
                }
                try {
                    field.isAccessible = true
                    fields.add(field)
                } catch (ignored: Throwable) {
                }
            }
            current = current.superclass
        }
        return fields
    }

    private fun hasStringListConstructor(type: Class<*>): Boolean {
        for (constructor: Constructor<*> in type.declaredConstructors) {
            val parameters = constructor.parameterTypes
            if (
                parameters.size == 2 &&
                parameters[0] == String::class.java &&
                (
                    java.util.List::class.java.isAssignableFrom(parameters[1]) ||
                        java.util.Collection::class.java.isAssignableFrom(parameters[1])
                    )
            ) {
                return true
            }
        }
        return false
    }

    private fun isNumericField(field: Field, value: Any?): Boolean {
        val type = field.type
        return type == java.lang.Long.TYPE ||
            type == Integer.TYPE ||
            type == java.lang.Short.TYPE ||
            type == java.lang.Byte.TYPE ||
            type == Long::class.javaObjectType ||
            type == Int::class.javaObjectType ||
            type == Short::class.javaObjectType ||
            type == Byte::class.javaObjectType ||
            Number::class.java.isAssignableFrom(type) ||
            value is Number
    }

    private fun firstNonNull(list: List<*>?): Any? {
        if (list == null) {
            return null
        }
        for (value in list) {
            if (value != null) {
                return value
            }
        }
        return null
    }

    private fun callNoArg(target: Any, methodName: String): Any? {
        return try {
            val method: Method = target.javaClass.getMethod(methodName)
            method.isAccessible = true
            method.invoke(target)
        } catch (ignored: Throwable) {
            null
        }
    }

    private fun stringMethod(target: Any, methodName: String): String {
        val value = callNoArg(target, methodName)
        return if (value is String) value else ""
    }

    private fun longMethod(target: Any, methodName: String): Long {
        val value = callNoArg(target, methodName)
        return if (value is Number) value.toLong() else 0L
    }

    private fun joinWords(words: List<LyricWordSnapshot>): String {
        val builder = StringBuilder()
        for (word in words) {
            if (word.text != null) {
                builder.append(word.text)
            }
        }
        return builder.toString()
    }

    private fun isBlank(value: String?): Boolean {
        return value == null || value.trim().isEmpty()
    }

    private fun describeLyrics(lyrics: LyricsSnapshot): String {
        var words = 0
        var translationWords = 0
        var translatedLines = 0
        var zeroDurationWords = 0
        var shortGaps = 0
        var minGap = Long.MAX_VALUE
        var lastEnd = Long.MIN_VALUE
        var first: String? = ""
        var firstTranslation: String? = ""
        var firstLine = ""
        val lines = lyrics.lines!!
        for (index in lines.indices) {
            val line = lines[index]
            if (index == 0) {
                first = line.text
                firstTranslation = line.translation
                firstLine = describeLine(line)
            }
            if (lastEnd != Long.MIN_VALUE) {
                val gap = line.startMs - lastEnd
                minGap = kotlin.math.min(minGap, gap)
                if (gap in 0L..80L) {
                    shortGaps++
                }
            }
            lastEnd = line.endMs
            if (line.words != null) {
                words += line.words.size
                for (word in line.words) {
                    if (word.endMs <= word.startMs) {
                        zeroDurationWords++
                    }
                }
            }
            if (line.translationWords != null) {
                translationWords += line.translationWords.size
            }
            if (!isBlank(line.translation)) {
                translatedLines++
            }
        }
        return "lyrics{source=" + lyrics.source +
            ",lines=" + lines.size +
            ",words=" + words +
            ",translationWords=" + translationWords +
            ",translatedLines=" + translatedLines +
            ",zeroDurationWords=" + zeroDurationWords +
            ",shortGaps<=80ms=" + shortGaps +
            ",minGap=" + (if (minGap == Long.MAX_VALUE) "n/a" else minGap.toString()) +
            ",first=" + preview(first) +
            ",firstTranslation=" + preview(firstTranslation) +
            ",firstLine=" + firstLine +
            "}"
    }

    private fun logTimelineDiagnostics(songId: String?, lines: List<LyricLineSnapshot>?) {
        if (!SaltDiagnostics.enabled() || lines == null || lines.isEmpty()) {
            return
        }

        var shortLines = 0
        var shortCredits = 0
        var shortGaps = 0
        var minDuration = Long.MAX_VALUE
        var minGap = Long.MAX_VALUE
        var lastEnd = Long.MIN_VALUE
        val samples = ArrayList<String>()

        for (index in lines.indices) {
            val line = lines[index]
            val duration = kotlin.math.max(0L, line.endMs - line.startMs)
            minDuration = kotlin.math.min(minDuration, duration)
            var gap = Long.MAX_VALUE
            if (lastEnd != Long.MIN_VALUE) {
                gap = line.startMs - lastEnd
                minGap = kotlin.math.min(minGap, gap)
                if (gap in 0L..80L) {
                    shortGaps++
                }
            }
            lastEnd = line.endMs

            val shortLine = duration <= INTRO_CREDIT_LINE_MAX_DURATION_MS
            val shortCredit = shortLine && isCreditText(line.text)
            if (shortLine) {
                shortLines++
            }
            if (shortCredit) {
                shortCredits++
            }
            if (
                samples.size < TIMELINE_DIAGNOSTIC_SAMPLE_LIMIT &&
                (index < 12 || shortLine || (gap in 0L..80L))
            ) {
                samples.add(
                    "line[$index]" +
                        " gap=" + (if (gap == Long.MAX_VALUE) "n/a" else gap.toString()) +
                        " " + describeLine(line),
                )
            }
        }

        SaltDiagnostics.trace(
            "extractor.timeline",
            "songId=" + songId +
                " lines=" + lines.size +
                " shortLines<=350ms=" + shortLines +
                " shortCredits=" + shortCredits +
                " shortGaps<=80ms=" + shortGaps +
                " minDuration=" + (if (minDuration == Long.MAX_VALUE) "n/a" else minDuration.toString()) +
                " minGap=" + (if (minGap == Long.MAX_VALUE) "n/a" else minGap.toString()) +
                " samples=" + samples,
        )
    }

    private fun describeLine(line: LyricLineSnapshot?): String {
        if (line == null) {
            return "null"
        }
        val words = line.words?.size ?: 0
        val translationWords = line.translationWords?.size ?: 0
        return "{begin=" + line.startMs +
            ",end=" + line.endMs +
            ",duration=" + kotlin.math.max(0L, line.endMs - line.startMs) +
            ",words=" + words +
            ",translationWords=" + translationWords +
            ",text=" + preview(line.text) +
            ",translation=" + preview(line.translation) +
            "}"
    }

    private fun findFirstRawWord(sourceLine: Any?): Any? {
        val schema = lineSchemaFor(sourceLine) ?: return null
        return firstNonNull(readListField(sourceLine, schema.wordsField))
    }

    private fun describeFields(source: Any?, maxFields: Int): String {
        if (source == null) {
            return "null"
        }
        val builder = StringBuilder()
        builder.append("{class=").append(source.javaClass.name).append(",fields=[")
        var count = 0
        var type: Class<*>? = source.javaClass
        while (type != null && type != Object::class.java && count < maxFields) {
            for (field in type.declaredFields) {
                if (count >= maxFields) {
                    break
                }
                if (Modifier.isStatic(field.modifiers)) {
                    continue
                }
                if (count > 0) {
                    builder.append(';')
                }
                builder.append(field.name)
                    .append(':')
                    .append(simpleName(field.type))
                    .append('=')
                try {
                    field.isAccessible = true
                    builder.append(describeValue(field.get(source)))
                } catch (throwable: Throwable) {
                    builder.append("<")
                        .append(throwable.javaClass.simpleName)
                        .append(">")
                }
                count++
            }
            type = type.superclass
        }
        builder.append("],shown=").append(count).append('}')
        return builder.toString()
    }

    private fun describeValue(value: Any?): String {
        if (value == null) {
            return "null"
        }
        if (value is String) {
            return "String(" + preview(value) + ")"
        }
        if (value is Number || value is Boolean || value is Char) {
            return value.toString()
        }
        if (value is List<*>) {
            val first = firstNonNull(value)
            return "List(size=" + value.size +
                ",first=" + (first?.javaClass?.name ?: "null") +
                ")"
        }
        return value.javaClass.name
    }

    private fun simpleName(type: Class<*>): String {
        if (type.isArray) {
            return simpleName(type.componentType!!) + "[]"
        }
        val name = type.simpleName
        return if (name == null || name.isEmpty()) type.name else name
    }

    private fun preview(value: String?): String {
        if (value == null) {
            return "null"
        }
        val trimmed = value.replace('\n', ' ').replace('\r', ' ').trim()
        return if (trimmed.length <= 36) trimmed else trimmed.substring(0, 36) + "..."
    }

    private fun nullToEmpty(value: String?): String {
        return value ?: ""
    }

    private fun emptyToNull(value: String?): String? {
        return if (isBlank(value)) null else value
    }

    private class LineSchema(
        val startField: Field,
        val endField: Field,
        val wordsField: Field,
        val translationField: Field?,
        val textField: Field?,
    ) {
        fun describe(): String {
            return "fields{start=" + startField.name +
                ",end=" + endField.name +
                ",words=" + wordsField.name +
                ",translation=" + (translationField?.name ?: "null") +
                ",text=" + (textField?.name ?: "null") +
                "}"
        }
    }

    private class WordSchema(
        val startField: Field,
        val endField: Field,
        val textField: Field,
    ) {
        fun describe(): String {
            return "fields{start=" + startField.name +
                ",end=" + endField.name +
                ",text=" + textField.name +
                "}"
        }
    }
}
