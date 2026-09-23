package com.macroandroid.automation.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class CompareOp { LT, LE, EQ, NE, GE, GT }

@Serializable
sealed interface Condition {
    @Serializable
    @SerialName("varEquals")
    data class VarEquals(val name: String, val value: VariableValue) : Condition

    @Serializable
    @SerialName("varCompare")
    data class VarCompare(val name: String, val op: CompareOp, val value: Long) : Condition

    @Serializable
    @SerialName("varContains")
    data class VarContains(val name: String, val needle: TextValue) : Condition

    /** Requires the accessibility service. */
    @Serializable
    @SerialName("nodeExists")
    data class NodeExists(val selector: NodeSelector) : Condition

    @Serializable
    @SerialName("appInstalled")
    data class AppInstalled(val packageName: String) : Condition

    @Serializable
    @SerialName("not")
    data class Not(val inner: Condition) : Condition

    @Serializable
    @SerialName("all")
    data class All(val conditions: List<Condition>) : Condition

    @Serializable
    @SerialName("any")
    data class Any(val conditions: List<Condition>) : Condition

    /** True when evaluating this condition needs the accessibility service. */
    val needsAccessibility: Boolean
        get() = when (this) {
            is NodeExists -> true
            is Not -> inner.needsAccessibility
            is All -> conditions.any { it.needsAccessibility }
            is Any -> conditions.any { it.needsAccessibility }
            else -> false
        }
}
