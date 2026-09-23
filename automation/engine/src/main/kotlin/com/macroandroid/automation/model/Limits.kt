package com.macroandroid.automation.model

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Hard limits of schema v1 (docs/phase-1/08-macro-schema.md §3). */
object MacroLimits {
    const val NAME_MAX = 80
    const val DESCRIPTION_MAX = 500
    const val PROFILE_MAX = 40
    const val TAGS_MAX = 10
    const val TAG_MAX = 30
    const val TOP_LEVEL_STEPS_MAX = 100
    const val EXPANDED_LEAVES_MAX = 200
    const val NESTING_DEPTH_MAX = 4
    const val REPEAT_MAX = 100
    const val PARALLEL_MIN = 2
    const val PARALLEL_MAX = 8
    const val VARIABLES_MAX = 32
    const val VARIABLE_NAME_MAX = 32
    const val LITERAL_MAX = 4_000
    const val URL_MAX = 2_048
    const val REGEX_MAX = 200
    const val LABEL_MAX = 40
    const val NOTIFICATION_TITLE_MAX = 80
    const val NOTIFICATION_TEXT_MAX = 500
    const val LOG_MESSAGE_MAX = 500
    const val CONDITION_LIST_MAX = 8
    const val CONCAT_PARTS_MAX = 16
    const val SELECTOR_INDEX_MAX = 50
    const val SCROLL_TIMES_MAX = 20
    const val CLICKABLE_ANCESTOR_WALK = 5
    const val RETRY_ATTEMPTS_MAX = 5
    const val IMPORT_BYTES_MAX = 1024 * 1024
    const val IMPORT_JSON_DEPTH_MAX = 16
    const val IMPORT_MACROS_MAX = 50
    const val SCHEDULE_INTERVAL_MIN_MINUTES = 15
    const val SCHEDULE_INTERVAL_MAX_MINUTES = 7 * 24 * 60

    val TOTAL_TIMEOUT_MAX: Duration = 2.hours
    val STEP_TIMEOUT_MAX: Duration = 10.minutes
    val STEP_TIMEOUT_MIN: Duration = 100.milliseconds
    val WAIT_MIN: Duration = 10.milliseconds
    val WAIT_MAX: Duration = 10.minutes
    val RETRY_INITIAL_DELAY_MAX: Duration = 60.seconds
    val RETRY_MAX_DELAY_MAX: Duration = 5.minutes
    val BLOCKED_TIMEOUT_MAX: Duration = 1.hours
    val LOCK_ACQUIRE_TIMEOUT_MAX: Duration = 10.minutes
}
