package com.macroandroid.core.common.logging

/** Log severity; ordinal order matters (INFO < WARN < ERROR). */
enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/**
 * Minimal logging façade. Implementations decide the sink (Logcat in debug, in-memory ring + Room for
 * execution logs). Messages passed here must already be free of secrets; use [Redactor] when in doubt.
 */
interface Logger {
    fun log(level: LogLevel, tag: String, message: String, throwable: Throwable? = null)

    fun d(tag: String, message: String) = log(LogLevel.DEBUG, tag, message)
    fun i(tag: String, message: String) = log(LogLevel.INFO, tag, message)
    fun w(tag: String, message: String, throwable: Throwable? = null) = log(LogLevel.WARN, tag, message, throwable)
    fun e(tag: String, message: String, throwable: Throwable? = null) = log(LogLevel.ERROR, tag, message, throwable)

    /** Logger that drops everything; used as a safe default in tests and pure modules. */
    object Noop : Logger {
        override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) = Unit
    }
}

/** Logger writing to stdout; used by JVM unit tests and tooling. */
class PrintLogger(private val minLevel: LogLevel = LogLevel.DEBUG) : Logger {
    override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        if (level.ordinal < minLevel.ordinal) return
        println("${level.name.first()}/$tag: $message")
        throwable?.printStackTrace()
    }
}
