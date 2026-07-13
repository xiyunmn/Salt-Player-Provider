package com.xiyunmn.salthook.diagnostics

import android.app.Application
import android.os.SystemClock
import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.ConcurrentHashMap

object SaltDiagnostics {
    private const val TAG = "SaltLyricon"

    private val lastLogMs = ConcurrentHashMap<String, Long>()

    @Volatile
    private var xposed: XposedInterface? = null

    @JvmStatic
    fun setXposed(value: XposedInterface?) {
        xposed = value
    }

    @JvmStatic
    fun setHostApplication(application: Application?) {
        // Reserved for future host-scoped diagnostics. No file logging is created.
    }

    @JvmStatic
    fun enabled(): Boolean = false

    @JvmStatic
    fun now(): Long = SystemClock.elapsedRealtimeNanos()

    @JvmStatic
    fun elapsedMs(startNs: Long): Long {
        return (SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000L
    }

    @JvmStatic
    fun count(name: String?) {
    }

    @JvmStatic
    fun log(area: String?, message: String?) {
        val formatted = "[$area] $message"
        val logger = xposed
        if (logger != null) {
            logger.log(Log.INFO, TAG, formatted)
        }
    }

    @JvmStatic
    fun warn(area: String?, message: String?) {
        val formatted = "[$area] $message"
        val logger = xposed
        if (logger != null) {
            logger.log(Log.WARN, TAG, formatted)
        }
    }

    @JvmStatic
    fun warn(area: String?, message: String?, throwable: Throwable?) {
        val formatted = "[$area] $message"
        val logger = xposed
        if (logger != null) {
            logger.log(Log.WARN, TAG, formatted, throwable)
        }
    }

    @JvmStatic
    fun trace(area: String?, message: String?) {
    }

    @JvmStatic
    fun slow(area: String?, operation: String?, startNs: Long, thresholdMs: Long, detail: String?) {
        val elapsedMs = elapsedMs(startNs)
        if (elapsedMs >= thresholdMs) {
            log(area, operation + " took " + elapsedMs + "ms" + suffix(detail))
        }
    }

    @JvmStatic
    fun logThrottled(key: String, area: String?, intervalMs: Long, message: String?) {
        val nowMs = SystemClock.elapsedRealtime()
        val lastMs = lastLogMs[key]
        if (lastMs != null && nowMs - lastMs < intervalMs) {
            return
        }
        lastLogMs[key] = nowMs
        log(area, message)
    }

    private fun suffix(detail: String?): String {
        return if (detail == null || detail.isEmpty()) "" else " ($detail)"
    }
}
