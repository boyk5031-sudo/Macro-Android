package com.macroandroid.core.platform

import android.util.Log
import com.macroandroid.core.common.logging.LogLevel
import com.macroandroid.core.common.logging.Logger

/**
 * Logcat-backed [Logger]. DEBUG is dropped in release builds; callers must redact before logging
 * (see `core:common` `Redactor`), because logcat is readable by connected debuggers.
 */
class AndroidLogger(private val debugEnabled: Boolean) : Logger {
    override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        val t = tag.take(MAX_TAG)
        when (level) {
            LogLevel.DEBUG -> if (debugEnabled) Log.d(t, message, throwable)
            LogLevel.INFO -> Log.i(t, message, throwable)
            LogLevel.WARN -> Log.w(t, message, throwable)
            LogLevel.ERROR -> Log.e(t, message, throwable)
        }
    }

    private companion object {
        const val MAX_TAG = 23
    }
}
