package com.macroandroid.automation.model

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
@JvmInline
value class MacroId(val value: String) {
    override fun toString(): String = value

    companion object {
        @OptIn(ExperimentalUuidApi::class)
        fun random(): MacroId = MacroId(Uuid.random().toString())
    }
}

@Serializable
@JvmInline
value class StepId(val value: String) {
    override fun toString(): String = value

    companion object {
        @OptIn(ExperimentalUuidApi::class)
        fun random(): StepId = StepId(Uuid.random().toString())
    }
}

@Serializable
@JvmInline
value class ScheduleId(val value: String) {
    override fun toString(): String = value

    companion object {
        @OptIn(ExperimentalUuidApi::class)
        fun random(): ScheduleId = ScheduleId(Uuid.random().toString())
    }
}

@Serializable
@JvmInline
value class ExecutionId(val value: String) {
    override fun toString(): String = value

    companion object {
        @OptIn(ExperimentalUuidApi::class)
        fun random(): ExecutionId = ExecutionId(Uuid.random().toString())
    }
}
