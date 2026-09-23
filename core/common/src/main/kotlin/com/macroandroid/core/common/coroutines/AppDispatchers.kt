package com.macroandroid.core.common.coroutines

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import javax.inject.Qualifier

/** Injected dispatchers so every coroutine in production is testable with a `TestDispatcher`. */
data class AppDispatchers(
    val io: CoroutineDispatcher,
    val default: CoroutineDispatcher,
    val main: CoroutineDispatcher,
    val mainImmediate: CoroutineDispatcher,
)

/** Application-scoped supervisor scope for fire-and-forget work that must outlive a ViewModel. */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class ApplicationScope

typealias AppCoroutineScope = CoroutineScope
