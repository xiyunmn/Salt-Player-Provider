package com.xiyunmn.salthook.provider

import android.app.Application
import android.media.session.PlaybackState
import com.xiyunmn.salthook.core.LyricLineSnapshot
import com.xiyunmn.salthook.core.LyricWordSnapshot
import com.xiyunmn.salthook.core.LyricsSnapshot
import com.xiyunmn.salthook.core.SongSnapshot
import com.xiyunmn.salthook.diagnostics.SaltDiagnostics
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderConstants
import io.github.proify.lyricon.provider.ProviderMetadata
import io.github.proify.lyricon.provider.RemotePlayer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LyriconProviderBridge(private val application: Application) {
    private val publisher: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(
        NamedThreadFactory("SaltLyricon-Publisher"),
    )

    @Volatile
    private var provider: LyriconProvider? = null

    private var currentSong: SongSnapshot? = null
    private var currentLyrics: LyricsSnapshot? = null
    private var currentPositionMs = 0L
    private var playing = false
    private var playbackState: PlaybackState? = null
    private var songPublishVersion = 0
    private var playbackPublishVersion = 0
    private var lastPublishedSongSignature: String? = null
    private var lastAcceptedPlaybackUpdateMs = Long.MIN_VALUE
    private val pendingPublisherTasks = AtomicInteger()

    fun start() {
        val start = SaltDiagnostics.now()
        if (SaltDiagnostics.enabled()) {
            SaltDiagnostics.trace(
                "provider",
                "start requested app=" + application.packageName + " existing=" + (provider != null),
            )
        }
        val existing = provider
        if (existing != null) {
            val registerStart = SaltDiagnostics.now()
            existing.register()
            SaltDiagnostics.log(
                "provider",
                "re-registered existing provider in " + SaltDiagnostics.elapsedMs(registerStart) + "ms",
            )
            return
        }

        val metadata = mapOf(
            "source" to "SaltPlayer",
            "word_by_word" to "true",
        )

        val created = LyriconFactory.createProvider(
            context = application,
            providerPackageName = MODULE_PACKAGE,
            playerPackageName = PLAYER_PACKAGE,
            logo = null,
            metadata = ProviderMetadata(metadata),
            processName = application.packageName,
            providerService = null,
            centralPackageName = ProviderConstants.SYSTEM_UI_PACKAGE_NAME,
        )
        created.autoSync = true
        val registerStart = SaltDiagnostics.now()
        created.register()
        SaltDiagnostics.log(
            "provider",
            "created and registered provider createPlusRegisterMs=" +
                SaltDiagnostics.elapsedMs(start) + " registerMs=" + SaltDiagnostics.elapsedMs(registerStart),
        )
        val player = created.player
        player.setPositionUpdateInterval(ProviderConstants.DEFAULT_POSITION_UPDATE_INTERVAL.toInt())
        player.setDisplayTranslation(true)
        SaltDiagnostics.log(
            "provider",
            "player initialized autoSync=true positionInterval=" +
                ProviderConstants.DEFAULT_POSITION_UPDATE_INTERVAL + " displayTranslation=true",
        )

        synchronized(this) {
            if (provider == null) {
                provider = created
                if (SaltDiagnostics.enabled()) {
                    SaltDiagnostics.trace("provider", "provider accepted state=" + describeCurrentStateLocked())
                }
                requestSongPublishLocked()
                requestPlaybackPublishLocked()
            } else {
                SaltDiagnostics.warn("provider", "provider race detected, destroying duplicate")
                created.destroy()
            }
        }
    }

    @Synchronized
    fun updateSong(song: SongSnapshot?) {
        SaltDiagnostics.count("provider.updateSong")
        val previousSong = currentSong
        val previousLyrics = currentLyrics
        val sameSong = sameSong(previousSong, song)
        currentSong = song
        if (!sameSong) {
            lastAcceptedPlaybackUpdateMs = Long.MIN_VALUE
        }
        if (song == null) {
            currentLyrics = null
        } else if (currentLyrics != null && song.id != currentLyrics!!.songId) {
            if (SaltDiagnostics.enabled()) {
                SaltDiagnostics.trace(
                    "provider",
                    "clear lyrics because song changed song=" + describeSong(song) +
                        " lyricsSongId=" + currentLyrics!!.songId,
                )
            }
            currentLyrics = null
        }
        if (SaltDiagnostics.enabled()) {
            SaltDiagnostics.trace(
                "provider",
                "updateSong " + describeSong(song) + " state=" + describeCurrentStateLocked(),
            )
        }
        if (sameSong && previousLyrics === currentLyrics) {
            SaltDiagnostics.count("provider.updateSong.noop")
            if (SaltDiagnostics.enabled()) {
                SaltDiagnostics.trace(
                    "provider",
                    "skip duplicate updateSong " + describeSong(song) + " lyrics=" + describeLyrics(currentLyrics),
                )
            }
            return
        }
        requestSongPublishLocked()
    }

    @Synchronized
    fun updateLyrics(lyrics: LyricsSnapshot?) {
        SaltDiagnostics.count("provider.updateLyrics")
        val song = currentSong
        if (lyrics == null || song == null || song.id == lyrics.songId) {
            currentLyrics = lyrics
        } else {
            SaltDiagnostics.count("provider.lyrics.dropStale")
            if (SaltDiagnostics.enabled()) {
                SaltDiagnostics.trace(
                    "provider",
                    "ignore stale lyrics incomingSongId=" + lyrics.songId +
                        " currentSong=" + describeSong(song) +
                        " incomingSummary=" + describeLyrics(lyrics),
                )
            }
            return
        }
        if (SaltDiagnostics.enabled()) {
            SaltDiagnostics.trace(
                "provider",
                "updateLyrics accepted " + describeLyrics(lyrics) + " state=" + describeCurrentStateLocked(),
            )
        }
        requestSongPublishLocked()
    }

    @Synchronized
    fun updatePlaybackState(state: PlaybackState?) {
        SaltDiagnostics.count("provider.updatePlaybackState")
        if (shouldDropBackwardPlaybackStateLocked(state)) {
            return
        }
        playbackState = state
        if (state != null) {
            playing = state.state == PlaybackState.STATE_PLAYING
            val position = state.position
            if (position >= 0L) {
                currentPositionMs = position
            }
            val updateAt = state.lastPositionUpdateTime
            if (updateAt > 0L) {
                lastAcceptedPlaybackUpdateMs = updateAt
            }
        }
        if (SaltDiagnostics.enabled()) {
            SaltDiagnostics.trace(
                "provider",
                "updatePlaybackState object=" + describePlaybackState(state) +
                    " state=" + describeCurrentStateLocked(),
            )
        }
        requestPlaybackPublishLocked()
    }

    private fun shouldDropBackwardPlaybackStateLocked(state: PlaybackState?): Boolean {
        if (state == null || currentSong == null || !playing || state.state != PlaybackState.STATE_PLAYING) {
            return false
        }
        val position = state.position
        val updateAt = state.lastPositionUpdateTime
        if (position < 0L || updateAt <= 0L || lastAcceptedPlaybackUpdateMs == Long.MIN_VALUE) {
            return false
        }
        val backwardMs = currentPositionMs - position
        val updateDeltaMs = updateAt - lastAcceptedPlaybackUpdateMs
        if (
            backwardMs < STALE_BACKWARD_PLAYBACK_THRESHOLD_MS ||
            updateDeltaMs < 0L ||
            updateDeltaMs > STALE_BACKWARD_PLAYBACK_WINDOW_MS
        ) {
            return false
        }

        SaltDiagnostics.count("provider.playback.dropBackward")
        SaltDiagnostics.warn(
            "provider",
            "drop stale backward playback incoming=" + describePlaybackState(state) +
                " currentPosition=" + currentPositionMs +
                " backwardMs=" + backwardMs +
                " updateDeltaMs=" + updateDeltaMs +
                " song=" + describeSong(currentSong) +
                " lyrics=" + describeLyrics(currentLyrics),
        )
        return true
    }

    private fun requestSongPublishLocked() {
        if (provider == null) {
            if (SaltDiagnostics.enabled()) {
                SaltDiagnostics.trace(
                    "provider",
                    "skip song publish request: provider null " + describeCurrentStateLocked(),
                )
            }
            return
        }
        val version = ++songPublishVersion
        val pending = pendingPublisherTasks.incrementAndGet()
        val delayMs = if (currentLyrics == null) {
            SONG_PUBLISH_WAIT_LYRICS_DELAY_MS
        } else {
            SONG_PUBLISH_WITH_LYRICS_DELAY_MS
        }
        if (SaltDiagnostics.enabled()) {
            SaltDiagnostics.trace(
                "provider",
                "enqueue songPublish version=" + version +
                    " delayMs=" + delayMs +
                    " pending=" + pending + " " + describeCurrentStateLocked(),
            )
        }
        publisher.schedule(
            { runPublisherTask("songPublish", version) { publishSong(version) } },
            delayMs,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun requestPlaybackPublishLocked() {
        if (provider == null) {
            if (SaltDiagnostics.enabled()) {
                SaltDiagnostics.trace(
                    "provider",
                    "skip playback publish request: provider null " + describeCurrentStateLocked(),
                )
            }
            return
        }
        val version = ++playbackPublishVersion
        val pending = pendingPublisherTasks.incrementAndGet()
        if (SaltDiagnostics.enabled()) {
            SaltDiagnostics.trace(
                "provider",
                "enqueue playbackPublish version=" + version +
                    " pending=" + pending + " " + describePlaybackState(playbackState) +
                    " position=" + currentPositionMs + " playing=" + playing,
            )
        }
        publisher.execute { runPublisherTask("playbackPublish", version) { publishPlayback(version) } }
    }

    private fun runPublisherTask(operation: String, version: Int, runnable: () -> Unit) {
        val start = SaltDiagnostics.now()
        try {
            runnable()
        } catch (throwable: Throwable) {
            SaltDiagnostics.warn("provider", "$operation v=$version failed", throwable)
        } finally {
            val pending = pendingPublisherTasks.decrementAndGet()
            if (SaltDiagnostics.enabled()) {
                val elapsed = SaltDiagnostics.elapsedMs(start)
                SaltDiagnostics.trace(
                    "provider",
                    "$operation v=$version taskDone elapsedMs=$elapsed pending=$pending",
                )
            }
            SaltDiagnostics.slow("provider", "$operation v=$version", start, 50L, "pending=$pending")
        }
    }

    private fun publishSong(version: Int) {
        val songSnapshot: SongSnapshot
        val lyricsSnapshot: LyricsSnapshot?
        synchronized(this) {
            val song = currentSong
            if (version != songPublishVersion || provider == null || song == null) {
                SaltDiagnostics.count("provider.publishSong.drop")
                if (SaltDiagnostics.enabled()) {
                    SaltDiagnostics.trace(
                        "provider",
                        "drop songPublish version=" + version +
                            " latest=" + songPublishVersion +
                            " hasProvider=" + (provider != null) +
                            " hasSong=" + (currentSong != null),
                    )
                }
                return
            }
            songSnapshot = song
            lyricsSnapshot = currentLyrics
        }

        val signature = songSignature(songSnapshot, lyricsSnapshot)
        if (signature == lastPublishedSongSignature) {
            SaltDiagnostics.count("provider.publishSong.duplicate")
            if (SaltDiagnostics.enabled()) {
                SaltDiagnostics.trace(
                    "provider",
                    "skip duplicate setSong version=" + version +
                        " signatureHash=" + signature.hashCode() +
                        " signatureLength=" + signature.length +
                        " " + describeSong(songSnapshot) +
                        " " + describeLyrics(lyricsSnapshot),
                )
            }
            publishCurrentPlayback("duplicate-song v=$version")
            return
        }

        val convertStart = SaltDiagnostics.now()
        val song: Song = LyriconSongMapper.toSong(songSnapshot, lyricsSnapshot)
        val convertMs = SaltDiagnostics.elapsedMs(convertStart)
        val player: RemotePlayer
        synchronized(this) {
            if (
                version != songPublishVersion ||
                provider == null ||
                songSnapshot !== currentSong ||
                lyricsSnapshot !== currentLyrics
            ) {
                SaltDiagnostics.count("provider.publishSong.dropAfterConvert")
                if (SaltDiagnostics.enabled()) {
                    SaltDiagnostics.trace(
                        "provider",
                        "drop songPublish after convert version=" + version +
                            " latest=" + songPublishVersion +
                            " hasProvider=" + (provider != null) +
                            " songChanged=" + (songSnapshot !== currentSong) +
                            " lyricsChanged=" + (lyricsSnapshot !== currentLyrics),
                    )
                }
                return
            }
            player = provider!!.player
        }
        val setSongStart = SaltDiagnostics.now()
        player.setSong(song)
        val setSongMs = SaltDiagnostics.elapsedMs(setSongStart)
        if (SaltDiagnostics.enabled()) {
            SaltDiagnostics.trace(
                "provider",
                "setSong version=" + version +
                    " convertMs=" + convertMs +
                    " setSongMs=" + setSongMs +
                    " signatureHash=" + signature.hashCode() +
                    " signatureLength=" + signature.length +
                    " " + describeSong(songSnapshot) +
                    " " + describeLyrics(lyricsSnapshot) +
                    " rich=" + LyriconSongMapper.describe(song),
            )
        }
        rememberPublishedSongSignature(signature, version)
        publishCurrentPlayback("after-setSong v=$version")
    }

    private fun publishPlayback(version: Int) {
        val player: RemotePlayer
        val playbackPlaying: Boolean
        val playbackPosition: Long
        val latestPlaybackState: PlaybackState?
        synchronized(this) {
            if (version != playbackPublishVersion || provider == null) {
                SaltDiagnostics.count("provider.publishPlayback.drop")
                if (SaltDiagnostics.enabled()) {
                    SaltDiagnostics.trace(
                        "provider",
                        "drop playbackPublish version=" + version +
                            " latest=" + playbackPublishVersion +
                            " hasProvider=" + (provider != null),
                    )
                }
                return
            }
            player = provider!!.player
            playbackPlaying = playing
            playbackPosition = currentPositionMs
            latestPlaybackState = playbackState
        }
        publishPlaybackToPlayer(player, playbackPlaying, playbackPosition, latestPlaybackState, "playback v=$version")
    }

    private fun publishCurrentPlayback(reason: String) {
        val player: RemotePlayer
        val playbackPlaying: Boolean
        val playbackPosition: Long
        val latestPlaybackState: PlaybackState?
        synchronized(this) {
            if (provider == null) {
                if (SaltDiagnostics.enabled()) {
                    SaltDiagnostics.trace("provider", "skip publishCurrentPlayback no provider reason=$reason")
                }
                return
            }
            player = provider!!.player
            playbackPlaying = playing
            playbackPosition = currentPositionMs
            latestPlaybackState = playbackState
        }
        publishPlaybackToPlayer(player, playbackPlaying, playbackPosition, latestPlaybackState, reason)
    }

    @Synchronized
    private fun rememberPublishedSongSignature(signature: String, version: Int) {
        if (provider == null || currentSong == null) {
            if (SaltDiagnostics.enabled()) {
                SaltDiagnostics.trace("provider", "skip remember signature no current state version=$version")
            }
            return
        }

        val currentSignature = songSignature(currentSong!!, currentLyrics)
        if (signature == currentSignature) {
            lastPublishedSongSignature = signature
            if (SaltDiagnostics.enabled()) {
                SaltDiagnostics.trace("provider", "remember published signature version=$version hash=" + signature.hashCode())
            }
        } else if (SaltDiagnostics.enabled()) {
            SaltDiagnostics.trace(
                "provider",
                "skip stale published signature version=" + version +
                    " publishedHash=" + signature.hashCode() +
                    " currentHash=" + currentSignature.hashCode(),
            )
        }
    }

    private fun describeCurrentStateLocked(): String {
        return "state{song=" + describeSong(currentSong) +
            ",lyrics=" + describeLyrics(currentLyrics) +
            ",playing=" + playing +
            ",position=" + currentPositionMs +
            ",playback=" + describePlaybackState(playbackState) +
            ",songVersion=" + songPublishVersion +
            ",playbackVersion=" + playbackPublishVersion +
            ",pending=" + pendingPublisherTasks.get() +
            "}"
    }

    private class NamedThreadFactory(private val name: String) : ThreadFactory {
        override fun newThread(runnable: Runnable): Thread {
            val thread = Thread(
                {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                    runnable.run()
                },
                name,
            )
            thread.priority = Thread.NORM_PRIORITY - 1
            return thread
        }
    }

    private companion object {
        const val MODULE_PACKAGE = "com.xiyunmn.salthook"
        const val PLAYER_PACKAGE = "com.salt.music"
        const val SONG_PUBLISH_WITH_LYRICS_DELAY_MS = 16L
        const val SONG_PUBLISH_WAIT_LYRICS_DELAY_MS = 250L
        const val STALE_BACKWARD_PLAYBACK_THRESHOLD_MS = 1500L
        const val STALE_BACKWARD_PLAYBACK_WINDOW_MS = 1500L

        fun publishPlaybackToPlayer(
            player: RemotePlayer,
            playbackPlaying: Boolean,
            playbackPosition: Long,
            playbackState: PlaybackState?,
            reason: String,
        ) {
            val start = SaltDiagnostics.now()
            if (playbackState != null) {
                player.setPlaybackState(playbackState)
                if (SaltDiagnostics.enabled()) {
                    SaltDiagnostics.trace(
                        "provider",
                        "publishPlayback mode=object reason=" + reason +
                            " elapsedMs=" + SaltDiagnostics.elapsedMs(start) +
                            " " + describePlaybackState(playbackState),
                    )
                }
            } else {
                player.setPlaybackState(playbackPlaying)
                player.setPosition(playbackPosition)
                if (SaltDiagnostics.enabled()) {
                    SaltDiagnostics.trace(
                        "provider",
                        "publishPlayback mode=manual reason=" + reason +
                            " elapsedMs=" + SaltDiagnostics.elapsedMs(start) +
                            " playing=" + playbackPlaying + " position=" + playbackPosition,
                    )
                }
            }
        }

        fun sameSong(left: SongSnapshot?, right: SongSnapshot?): Boolean {
            if (left === right) {
                return true
            }
            if (left == null || right == null) {
                return false
            }
            return stringEquals(left.id, right.id) &&
                stringEquals(left.title, right.title) &&
                stringEquals(left.artist, right.artist) &&
                left.durationMs == right.durationMs
        }

        fun stringEquals(left: String?, right: String?): Boolean {
            return if (left == null) right == null else left == right
        }

        fun songSignature(song: SongSnapshot, lyrics: LyricsSnapshot?): String {
            val builder = StringBuilder()
            builder.append(song.id).append('|')
                .append(song.title).append('|')
                .append(song.artist).append('|')
                .append(song.durationMs)
            if (lyrics == null) {
                return builder.append("|no-lyrics").toString()
            }

            val lines = lyrics.lines!!
            builder.append('|').append(lyrics.source).append('|').append(lines.size)
            for (line in lines) {
                builder.append('|').append(line.startMs)
                    .append(',').append(line.endMs)
                    .append(',').append(line.text)
                    .append(',').append(line.translation)
                appendWordsSignature(builder, line.words)
                appendWordsSignature(builder, line.translationWords)
            }
            return builder.toString()
        }

        fun appendWordsSignature(builder: StringBuilder, words: List<LyricWordSnapshot>?) {
            if (words == null) {
                builder.append(",words:null")
                return
            }
            builder.append(",words:").append(words.size)
            for (word in words) {
                builder.append('[')
                    .append(word.startMs)
                    .append('-')
                    .append(word.endMs)
                    .append(':')
                    .append(word.text)
                    .append(']')
            }
        }

        fun describeSong(song: SongSnapshot?): String {
            if (song == null) {
                return "null"
            }
            return "{id=" + song.id +
                ",title=" + preview(song.title) +
                ",artist=" + preview(song.artist) +
                ",duration=" + song.durationMs + "}"
        }

        fun describeLyrics(lyrics: LyricsSnapshot?): String {
            if (lyrics == null) {
                return "lyrics=null"
            }
            var words = 0
            var translationWords = 0
            var translatedLines = 0
            var emptyWordsLines = 0
            var zeroDurationWords = 0
            var shortGaps = 0
            var minGap = Long.MAX_VALUE
            var minLineDuration = Long.MAX_VALUE
            var maxLineDuration = Long.MIN_VALUE
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
                val lineDuration = kotlin.math.max(0L, line.endMs - line.startMs)
                minLineDuration = kotlin.math.min(minLineDuration, lineDuration)
                maxLineDuration = kotlin.math.max(maxLineDuration, lineDuration)
                if (lastEnd != Long.MIN_VALUE) {
                    val gap = line.startMs - lastEnd
                    minGap = kotlin.math.min(minGap, gap)
                    if (gap in 0L..80L) {
                        shortGaps++
                    }
                }
                lastEnd = line.endMs
                if (line.words == null || line.words.isEmpty()) {
                    emptyWordsLines++
                } else {
                    words += line.words.size
                    for (word in line.words) {
                        if (word.endMs <= word.startMs) {
                            zeroDurationWords++
                        }
                    }
                }
                if (!isBlank(line.translation)) {
                    translatedLines++
                }
                if (line.translationWords != null) {
                    translationWords += line.translationWords.size
                }
            }
            if (lines.isEmpty()) {
                minLineDuration = 0L
                maxLineDuration = 0L
            }
            return "lyrics{songId=" + lyrics.songId +
                ",source=" + lyrics.source +
                ",lines=" + lines.size +
                ",words=" + words +
                ",translationWords=" + translationWords +
                ",translatedLines=" + translatedLines +
                ",emptyWordsLines=" + emptyWordsLines +
                ",zeroDurationWords=" + zeroDurationWords +
                ",shortGaps<=80ms=" + shortGaps +
                ",minGap=" + (if (minGap == Long.MAX_VALUE) "n/a" else minGap.toString()) +
                ",minLineDuration=" + minLineDuration +
                ",maxLineDuration=" + maxLineDuration +
                ",first=" + preview(first) +
                ",firstTranslation=" + preview(firstTranslation) +
                ",firstLine=" + firstLine +
                "}"
        }

        fun describeLine(line: LyricLineSnapshot?): String {
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

        fun describePlaybackState(state: PlaybackState?): String {
            if (state == null) {
                return "null"
            }
            return "{state=" + state.state +
                ",position=" + state.position +
                ",speed=" + state.playbackSpeed +
                ",updated=" + state.lastPositionUpdateTime +
                "}"
        }

        fun preview(value: String?): String {
            if (value == null) {
                return "null"
            }
            val trimmed = value.replace('\n', ' ').replace('\r', ' ').trim()
            return if (trimmed.length <= 36) trimmed else trimmed.substring(0, 36) + "..."
        }

        fun isBlank(value: String?): Boolean {
            return value == null || value.trim().isEmpty()
        }
    }
}
