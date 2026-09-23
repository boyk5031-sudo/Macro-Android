package com.macroandroid.automation.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Reference to an encrypted value stored outside the macro JSON (core:database `secure_values`). */
@Serializable
data class SecureValueRef(
    val id: String,
    /** True when exported without the value; the validator refuses to run such macros. */
    val redacted: Boolean = false,
)

@Serializable
sealed interface VariableValue {
    @Serializable
    @SerialName("str")
    data class Str(val value: String) : VariableValue

    @Serializable
    @SerialName("int")
    data class Int64(val value: Long) : VariableValue

    @Serializable
    @SerialName("bool")
    data class Bool(val value: Boolean) : VariableValue

    @Serializable
    @SerialName("secure")
    data class Secure(val ref: SecureValueRef) : VariableValue
}

/** Text that may be a literal, a variable reference, a secure reference, or a template. */
@Serializable
sealed interface TextValue {
    @Serializable
    @SerialName("literal")
    data class Literal(val text: String) : TextValue

    @Serializable
    @SerialName("var")
    data class Var(val name: String) : TextValue

    @Serializable
    @SerialName("secure")
    data class Secure(val ref: SecureValueRef) : TextValue

    /** Literal with `{{name}}` placeholders; only non-secure variables may be interpolated. */
    @Serializable
    @SerialName("template")
    data class Template(val template: String) : TextValue {
        companion object {
            val PLACEHOLDER: Regex = Regex("\\{\\{\\s*([a-zA-Z_][a-zA-Z0-9_]{0,31})\\s*}}")
        }

        fun referencedVariables(): Set<String> = PLACEHOLDER.findAll(template).map { it.groupValues[1] }.toSet()
    }
}

/** A value produced at run time; secure values are carried separately so they never reach logs. */
sealed interface RuntimeValue {
    data class Text(val value: String) : RuntimeValue
    data class Int64(val value: Long) : RuntimeValue
    data class Bool(val value: Boolean) : RuntimeValue

    /** A resolved secret: the plaintext is only readable via [reveal]; `toString` is redacted. */
    class Secret(private val plaintext: String) : RuntimeValue {
        fun reveal(): String = plaintext
        override fun toString(): String = "Secret(\u2022\u2022\u2022\u2022)"
        override fun equals(other: Any?): Boolean = other is Secret && other.plaintext == plaintext
        override fun hashCode(): Int = plaintext.hashCode()
    }

    val isSecret: Boolean get() = this is Secret

    fun asDisplayString(): String = when (this) {
        is Text -> value
        is Int64 -> value.toString()
        is Bool -> value.toString()
        is Secret -> toString()
    }
}
