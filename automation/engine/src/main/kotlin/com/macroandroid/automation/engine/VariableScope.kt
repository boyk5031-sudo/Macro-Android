package com.macroandroid.automation.engine

import com.macroandroid.automation.model.RuntimeValue
import com.macroandroid.automation.model.TextValue
import com.macroandroid.automation.model.VariableValue
import com.macroandroid.core.common.error.AppResult
import com.macroandroid.core.common.error.ErrorCode
import com.macroandroid.core.common.logging.Redactor

/**
 * Mutable variable table for one execution. Secrets are stored as [RuntimeValue.Secret] and never
 * interpolated into templates; [describe] is safe to log.
 */
class VariableScope(initial: Map<String, RuntimeValue> = emptyMap()) {
    private val values = LinkedHashMap(initial)

    operator fun get(name: String): RuntimeValue? = values[name]

    operator fun set(name: String, value: RuntimeValue) {
        values[name] = value
    }

    fun snapshot(): Map<String, RuntimeValue> = values.toMap()

    fun isSecret(name: String): Boolean = values[name]?.isSecret == true

    /** Resolves a [TextValue] to its runtime value. Secure refs must already be in [secureValues]. */
    fun resolve(text: TextValue, secureValues: Map<String, String>): AppResult<RuntimeValue> = when (text) {
        is TextValue.Literal -> AppResult.ok(RuntimeValue.Text(text.text))
        is TextValue.Var -> values[text.name]?.let { AppResult.ok(it) }
            ?: AppResult.err(ErrorCode.VARIABLE_UNDEFINED, text.name)
        is TextValue.Secure -> secureValues[text.ref.id]?.let { AppResult.ok(RuntimeValue.Secret(it)) }
            ?: AppResult.err(ErrorCode.SECURE_VALUE_UNAVAILABLE, text.ref.id)
        is TextValue.Template -> {
            var error: com.macroandroid.core.common.error.AppError? = null
            val rendered = TextValue.Template.PLACEHOLDER.replace(text.template) { m ->
                val name = m.groupValues[1]
                when (val v = values[name]) {
                    null -> { error = error ?: com.macroandroid.core.common.error.AppError(ErrorCode.VARIABLE_UNDEFINED, name); "" }
                    is RuntimeValue.Secret -> { error = error ?: com.macroandroid.core.common.error.AppError(ErrorCode.SECURE_IN_TEMPLATE, name); "" }
                    else -> v.asDisplayString()
                }
            }
            error?.let { AppResult.Err(it) } ?: AppResult.ok(RuntimeValue.Text(rendered))
        }
    }

    /** Plain string form for actions that need a `String`; secrets are revealed only here. */
    fun resolveString(text: TextValue, secureValues: Map<String, String>): AppResult<Pair<String, Boolean>> =
        when (val r = resolve(text, secureValues)) {
            is AppResult.Ok -> AppResult.ok(
                when (val v = r.value) {
                    is RuntimeValue.Secret -> v.reveal() to true
                    else -> v.asDisplayString() to false
                },
            )
            is AppResult.Err -> r
        }

    fun describe(): Map<String, String> = values.mapValues { (_, v) ->
        when (v) {
            is RuntimeValue.Secret -> Redactor.hashToken(v.reveal())
            else -> Redactor.clipForLog(v.asDisplayString())
        }
    }

    companion object {
        fun fromInitial(initial: Map<String, VariableValue>, secureValues: Map<String, String>): AppResult<VariableScope> {
            val map = LinkedHashMap<String, RuntimeValue>()
            for ((name, value) in initial) {
                map[name] = when (value) {
                    is VariableValue.Str -> RuntimeValue.Text(value.value)
                    is VariableValue.Int64 -> RuntimeValue.Int64(value.value)
                    is VariableValue.Bool -> RuntimeValue.Bool(value.value)
                    is VariableValue.Secure -> secureValues[value.ref.id]?.let { RuntimeValue.Secret(it) }
                        ?: return AppResult.err(ErrorCode.SECURE_VALUE_UNAVAILABLE, value.ref.id)
                }
            }
            return AppResult.ok(VariableScope(map))
        }

        fun toRuntime(value: VariableValue, secureValues: Map<String, String>): AppResult<RuntimeValue> = when (value) {
            is VariableValue.Str -> AppResult.ok(RuntimeValue.Text(value.value))
            is VariableValue.Int64 -> AppResult.ok(RuntimeValue.Int64(value.value))
            is VariableValue.Bool -> AppResult.ok(RuntimeValue.Bool(value.value))
            is VariableValue.Secure -> secureValues[value.ref.id]?.let { AppResult.ok(RuntimeValue.Secret(it)) }
                ?: AppResult.err(ErrorCode.SECURE_VALUE_UNAVAILABLE, value.ref.id)
        }
    }
}
