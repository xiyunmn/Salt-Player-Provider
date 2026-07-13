package com.xiyunmn.salthook.xposed

import android.app.Application
import android.media.session.PlaybackState
import android.util.Log
import com.xiyunmn.salthook.core.LyricLineSnapshot
import com.xiyunmn.salthook.core.LyricsSnapshot
import com.xiyunmn.salthook.core.SongSnapshot
import com.xiyunmn.salthook.diagnostics.SaltDiagnostics
import com.xiyunmn.salthook.provider.LyriconProviderBridge
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class SaltPlayerHooks(
    private val xposed: XposedInterface,
    private val classLoader: ClassLoader,
) {
    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(
        NamedThreadFactory("SaltLyricon-Hooks"),
    )
    private val providerStarted = AtomicBoolean(false)
    private val controllerHooksInstalled = AtomicBoolean(false)

    @Volatile
    private var providerBridge: LyriconProviderBridge? = null

    @Volatile
    private var currentSong: SongSnapshot? = null

    fun install() {
        val start = SaltDiagnostics.now()
        SaltDiagnostics.log("hooks", "install begin")
        hookApplication()
        hookMediaSession()
        SaltDiagnostics.log("hooks", "install finished in " + SaltDiagnostics.elapsedMs(start) + "ms")
    }

    private fun hookApplication() {
        try {
            val applicationClass = classLoader.loadClass(SALT_APPLICATION_CLASS)
            val onCreate = applicationClass.getMethod("onCreate")
            xposed.hook(onCreate)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val thisObject = chain.getThisObject()
                    if (thisObject is Application) {
                        SaltDiagnostics.setHostApplication(thisObject)
                        installControllerHooks(thisObject)
                    }
                    val result = chain.proceed()
                    if (thisObject is Application) {
                        startProvider(thisObject)
                    }
                    result
                }
        } catch (throwable: Throwable) {
            xposed.log(Log.WARN, TAG, "Application hook failed", throwable)
        }
    }

    private fun startProvider(application: Application) {
        if (!providerStarted.compareAndSet(false, true)) {
            return
        }
        SaltDiagnostics.setHostApplication(application)
        installControllerHooks(application)
        SaltDiagnostics.trace("provider", "schedule start delayMs=$PROVIDER_START_DELAY_MS")
        providerBridge = LyriconProviderBridge(application)
        worker.schedule(
            {
                try {
                    val bridge = providerBridge
                    if (bridge != null) {
                        val start = SaltDiagnostics.now()
                        bridge.start()
                        xposed.log(Log.INFO, TAG, "Lyricon provider registered")
                        SaltDiagnostics.log("provider", "registered in " + SaltDiagnostics.elapsedMs(start) + "ms")
                    }
                } catch (throwable: Throwable) {
                    xposed.log(Log.ERROR, TAG, "Start Lyricon provider failed", throwable)
                    SaltDiagnostics.warn("provider", "start failed", throwable)
                }
            },
            PROVIDER_START_DELAY_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun installControllerHooks(application: Application) {
        if (!controllerHooksInstalled.compareAndSet(false, true)) {
            return
        }

        val start = SaltDiagnostics.now()
        try {
            val cache = HookMethodCache.open(application)
            val controllerClass = classLoader.loadClass(MUSIC_CONTROLLER_CLASS)
            xposed.log(Log.INFO, TAG, "SaltPlayer hook scaffold ready: " + controllerClass.name)
            hookSongMethods(controllerClass, cache)
            hookLyricsDocumentMethods(controllerClass, cache)
            SaltDiagnostics.log("hooks", "controller hooks installed in " + SaltDiagnostics.elapsedMs(start) + "ms")
        } catch (throwable: Throwable) {
            xposed.log(Log.ERROR, TAG, "Install MusicController hooks failed", throwable)
            SaltDiagnostics.warn("hooks", "controller install failed", throwable)
        }
    }

    private fun hookSongMethods(controllerClass: Class<*>, cache: HookMethodCache) {
        var methods = loadCachedSongMethods(controllerClass, cache)
        val cacheHit = methods.isNotEmpty()
        if (!cacheHit) {
            methods = scanSongMethods(controllerClass)
            if (methods.isNotEmpty()) {
                cache.writeMethods(CACHE_GROUP_SONG, methods)
            }
        }

        var count = 0
        for (method in methods) {
            val songArgIndex = findCurrentVersionSongArgument(method)
            if (songArgIndex < 0) {
                SaltDiagnostics.warn("hooks", "skip invalid song hook method=" + method.toGenericString())
                continue
            }
            hookSongMethod(method, songArgIndex)
            count++
        }

        xposed.log(Log.INFO, TAG, "Hooked MusicController song methods: $count")
        SaltDiagnostics.log("hooks", "song hook source=" + (if (cacheHit) "cache" else "scan") + " count=" + count)
    }

    private fun loadCachedSongMethods(controllerClass: Class<*>, cache: HookMethodCache): List<Method> {
        val methods = cache.readMethods(controllerClass, CACHE_GROUP_SONG, classLoader)
        if (methods.isEmpty()) {
            return methods
        }
        for (method in methods) {
            if (findCurrentVersionSongArgument(method) < 0) {
                SaltDiagnostics.warn("hooks", "cached song method no longer matches " + method.toGenericString())
                return ArrayList()
            }
        }
        return methods
    }

    private fun hookSongMethod(method: Method, songArgIndex: Int) {
        method.isAccessible = true
        val argIndex = songArgIndex
        val methodName = method.toGenericString()
        if (SaltDiagnostics.enabled()) {
            SaltDiagnostics.trace("hooks", "songMethod=$methodName songArgIndex=$argIndex")
        }
        xposed.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                val start = SaltDiagnostics.now()
                val song = SaltPlayerExtractors.readSong(chain.getArg(argIndex))
                if (song != null) {
                    SaltDiagnostics.count("song.callback")
                    currentSong = song
                    if (SaltDiagnostics.enabled()) {
                        SaltDiagnostics.trace(
                            "song",
                            "method=" + methodName +
                                " parsedIn=" + SaltDiagnostics.elapsedMs(start) + "ms " +
                                describeSong(song),
                        )
                    }
                    val bridge = providerBridge
                    if (bridge != null) {
                        bridge.updateSong(song)
                    }
                } else if (SaltDiagnostics.enabled()) {
                    SaltDiagnostics.trace(
                        "song",
                        "method=" + methodName + " parsed null in " + SaltDiagnostics.elapsedMs(start) + "ms",
                    )
                }
                result
            }
    }

    private fun hookLyricsDocumentMethods(controllerClass: Class<*>, cache: HookMethodCache) {
        var methods = loadCachedLyricsMethods(controllerClass, cache)
        val cacheHit = methods.isNotEmpty()
        if (!cacheHit) {
            methods = scanLyricsDocumentMethods(controllerClass)
            if (methods.isNotEmpty()) {
                cache.writeMethods(CACHE_GROUP_LYRICS, methods)
            }
        }

        var count = 0
        for (method in methods) {
            hookLyricsDocumentMethod(method)
            if (SaltDiagnostics.enabled()) {
                SaltDiagnostics.trace("hooks", "lyricsExactMethod=" + method.toGenericString())
            }
            count++
        }

        xposed.log(Log.INFO, TAG, "Hooked MusicController lyrics document methods: $count")
        SaltDiagnostics.log("hooks", "lyrics hook source=" + (if (cacheHit) "cache" else "scan") + " count=" + count)
    }

    private fun loadCachedLyricsMethods(controllerClass: Class<*>, cache: HookMethodCache): List<Method> {
        val methods = cache.readMethods(controllerClass, CACHE_GROUP_LYRICS, classLoader)
        if (methods.isEmpty()) {
            return methods
        }
        for (method in methods) {
            if (!isLyricsDocumentMethod(method)) {
                SaltDiagnostics.warn("hooks", "cached lyrics method no longer matches " + method.toGenericString())
                return ArrayList()
            }
        }
        return methods
    }

    private fun hookLyricsDocumentMethod(method: Method) {
        method.isAccessible = true
        val methodName = method.toGenericString()
        xposed.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                publishLyricsDocument(methodName, chain.getArg(0))
                result
            }
    }

    private fun publishLyricsDocument(methodName: String, lyricsObject: Any?) {
        val song = currentSong
        val bridge = providerBridge
        if (song == null || bridge == null) {
            if (SaltDiagnostics.enabled()) {
                SaltDiagnostics.trace(
                    "lyrics",
                    "drop before parse method=" + methodName +
                        " hasSong=" + (song != null) + " hasBridge=" + (bridge != null),
                )
            }
            return
        }
        val songId = song.id
        val lyricsClass = lyricsObject?.javaClass?.name ?: "null"
        SaltDiagnostics.count("lyrics.callback")
        if (SaltDiagnostics.enabled()) {
            SaltDiagnostics.trace("lyrics", "enqueue parse method=$methodName class=$lyricsClass songId=$songId")
        }
        worker.execute {
            val start = SaltDiagnostics.now()
            val lyrics = SaltPlayerExtractors.readLyrics(lyricsObject, songId)
            if (lyrics == null) {
                SaltDiagnostics.count("lyrics.parse.null")
                if (SaltDiagnostics.enabled()) {
                    SaltDiagnostics.trace(
                        "lyrics",
                        "parse null method=" + methodName +
                            " class=" + lyricsClass +
                            " elapsedMs=" + SaltDiagnostics.elapsedMs(start),
                    )
                }
                return@execute
            }
            SaltDiagnostics.count("lyrics.parse.ok")
            if (SaltDiagnostics.enabled()) {
                SaltDiagnostics.trace(
                    "lyrics",
                    "parsed method=" + methodName +
                        " elapsedMs=" + SaltDiagnostics.elapsedMs(start) +
                        " " + describeLyrics(lyrics),
                )
            }
            val latestSong = currentSong
            val latestBridge = providerBridge
            if (latestSong != null && latestSong.id == lyrics.songId && latestBridge != null) {
                latestBridge.updateLyrics(lyrics)
            } else {
                SaltDiagnostics.count("lyrics.drop.stale")
                if (SaltDiagnostics.enabled()) {
                    SaltDiagnostics.trace(
                        "lyrics",
                        "drop stale parsedSongId=" + lyrics.songId +
                            " latestSong=" + describeSong(latestSong) +
                            " hasBridge=" + (latestBridge != null),
                    )
                }
            }
        }
    }

    private fun hookMediaSession() {
        try {
            val mediaSessionClass = Class.forName("android.media.session.MediaSession")
            val setPlaybackState = mediaSessionClass.getMethod("setPlaybackState", PlaybackState::class.java)
            xposed.hook(setPlaybackState)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    val arg = chain.getArg(0)
                    if (arg is PlaybackState) {
                        publishPlaybackState(arg)
                    }
                    result
                }
            xposed.log(Log.INFO, TAG, "Hooked MediaSession.setPlaybackState")
        } catch (throwable: Throwable) {
            xposed.log(Log.WARN, TAG, "MediaSession hook failed", throwable)
        }
    }

    private fun publishPlaybackState(state: PlaybackState) {
        val bridge = providerBridge
        if (bridge == null) {
            if (SaltDiagnostics.enabled()) {
                SaltDiagnostics.trace("playback", "drop no bridge " + describePlaybackState(state))
            }
            return
        }
        SaltDiagnostics.count("playback.callback")
        if (SaltDiagnostics.enabled()) {
            SaltDiagnostics.trace("playback", describePlaybackState(state))
        }
        bridge.updatePlaybackState(state)
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

    companion object {
        const val SALT_PLAYER_PACKAGE = "com.salt.music"
        private const val TAG = "SaltLyricon"
        private const val SALT_APPLICATION_CLASS = "com.salt.music.App"
        private const val MUSIC_CONTROLLER_CLASS = "com.salt.music.service.MusicController"
        private const val SONG_CLASS = "com.salt.music.data.entry.Song"
        private const val CACHE_GROUP_SONG = "song"
        private const val CACHE_GROUP_LYRICS = "lyrics"
        private const val PROVIDER_START_DELAY_MS = 1500L

        private fun scanSongMethods(controllerClass: Class<*>): List<Method> {
            val methods = ArrayList<Method>()
            for (method in controllerClass.declaredMethods) {
                if (findCurrentVersionSongArgument(method) >= 0) {
                    methods.add(method)
                }
            }
            return methods
        }

        private fun scanLyricsDocumentMethods(controllerClass: Class<*>): List<Method> {
            val methods = ArrayList<Method>()
            for (method in controllerClass.declaredMethods) {
                if (isLyricsDocumentMethod(method)) {
                    methods.add(method)
                }
            }
            return methods
        }

        private fun isLyricsDocumentMethod(method: Method): Boolean {
            val parameterTypes = method.parameterTypes
            return method.returnType == Void.TYPE &&
                parameterTypes.size == 1 &&
                SaltPlayerExtractors.isLikelyLyricsDocumentClass(parameterTypes[0])
        }

        private fun findCurrentVersionSongArgument(method: Method): Int {
            val parameterTypes = method.parameterTypes
            val modifiers = method.modifiers
            if (
                Modifier.isStatic(modifiers) &&
                method.returnType == Void.TYPE &&
                parameterTypes.size == 4 &&
                isSongClass(parameterTypes[0]) &&
                parameterTypes[1] == java.lang.Long.TYPE &&
                parameterTypes[2] == java.lang.Long.TYPE &&
                parameterTypes[3] == Long::class.javaObjectType
            ) {
                return 0
            }
            if (
                !Modifier.isStatic(modifiers) &&
                method.returnType == Object::class.java &&
                parameterTypes.size == 2 &&
                isSongClass(parameterTypes[0])
            ) {
                return 0
            }
            return -1
        }

        private fun isSongClass(type: Class<*>): Boolean {
            return SONG_CLASS == type.name
        }

        private fun describeSong(song: SongSnapshot?): String {
            if (song == null) {
                return "song=null"
            }
            return "song{id=" + song.id +
                ",title=" + preview(song.title) +
                ",artist=" + preview(song.artist) +
                ",duration=" + song.durationMs + "}"
        }

        private fun describeLyrics(lyrics: LyricsSnapshot): String {
            var wordCount = 0
            var translated = 0
            var firstStart = -1L
            var lastEnd = -1L
            var first: String? = ""
            var firstTranslation: String? = ""
            val lines = lyrics.lines!!
            for (index in lines.indices) {
                val line: LyricLineSnapshot = lines[index]
                if (index == 0) {
                    firstStart = line.startMs
                    first = line.text
                    firstTranslation = line.translation
                }
                lastEnd = line.endMs
                if (line.words != null) {
                    wordCount += line.words.size
                }
                if (line.translation != null && line.translation.trim().isNotEmpty()) {
                    translated++
                }
            }
            return "lyrics{songId=" + lyrics.songId +
                ",source=" + lyrics.source +
                ",lines=" + lines.size +
                ",words=" + wordCount +
                ",translatedLines=" + translated +
                ",firstStart=" + firstStart +
                ",lastEnd=" + lastEnd +
                ",first=" + preview(first) +
                ",firstTranslation=" + preview(firstTranslation) +
                "}"
        }

        private fun describePlaybackState(state: PlaybackState?): String {
            if (state == null) {
                return "state=null"
            }
            return "state=" + state.state +
                " position=" + state.position +
                " speed=" + state.playbackSpeed +
                " updateAt=" + state.lastPositionUpdateTime
        }

        private fun preview(value: String?): String {
            if (value == null) {
                return "null"
            }
            val trimmed = value.replace('\n', ' ').trim()
            return if (trimmed.length <= 32) trimmed else trimmed.substring(0, 32) + "..."
        }
    }
}
