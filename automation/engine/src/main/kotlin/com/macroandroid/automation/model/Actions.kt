package com.macroandroid.automation.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** How a step may run relative to other steps and other executions. */
enum class ConcurrencyClass {
    /** No shared device state touched; may run in parallel with anything. */
    BACKGROUND_SAFE,

    /** Touches the screen/foreground; serialized under the global UI lock. */
    UI,
    ;

    infix fun max(other: ConcurrencyClass): ConcurrencyClass = if (ordinal >= other.ordinal) this else other
}

@Serializable
enum class GlobalActionKind { BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS }

@Serializable
enum class ScrollDirection { FORWARD, BACKWARD }

@Serializable
enum class NodeState { PRESENT, ABSENT }

@Serializable
enum class LogLevelParam { INFO, WARN }

/** Derived values computed by `SetVariable`. */
@Serializable
sealed interface Expression {
    @Serializable
    @SerialName("increment")
    data class Increment(val by: Long = 1) : Expression

    @Serializable
    @SerialName("concat")
    data class Concat(val parts: List<TextValue>) : Expression

    @Serializable
    @SerialName("now")
    data object Now : Expression

    /** Reads the text of a node; requires the accessibility service; stored redacted in logs. */
    @Serializable
    @SerialName("nodeText")
    data class NodeText(val selector: NodeSelector) : Expression
}

/**
 * Closed, typed set of actions (schema v1). The JSON discriminator is `type`.
 * There is intentionally no "run code" action of any kind.
 */
@Serializable
sealed interface ActionParameters {
    val concurrencyClass: ConcurrencyClass
    val needsAccessibility: Boolean
    val defaultTimeout: Duration

    /** Direct child step lists for container actions (used by validator and executor). */
    val childGroups: List<List<MacroStep>> get() = emptyList()

    @Serializable
    @SerialName("launchApp")
    data class LaunchApp(
        val packageName: String,
        val waitForWindow: Boolean = true,
        val windowWait: Duration = 5.seconds,
    ) : ActionParameters {
        override val concurrencyClass get() = ConcurrencyClass.UI
        override val needsAccessibility get() = false
        override val defaultTimeout get() = 15.seconds
    }

    @Serializable
    @SerialName("openUrl")
    data class OpenUrl(
        val url: TextValue,
        val preferPackage: String? = null,
    ) : ActionParameters {
        override val concurrencyClass get() = ConcurrencyClass.UI
        override val needsAccessibility get() = false
        override val defaultTimeout get() = 15.seconds
    }

    @Serializable
    @SerialName("wait")
    data class Wait(val duration: Duration) : ActionParameters {
        override val concurrencyClass get() = ConcurrencyClass.BACKGROUND_SAFE
        override val needsAccessibility get() = false
        override val defaultTimeout get() = duration + 1.seconds
    }

    @Serializable
    @SerialName("globalAction")
    data class GlobalAction(val action: GlobalActionKind) : ActionParameters {
        override val concurrencyClass get() = ConcurrencyClass.UI
        override val needsAccessibility get() = true
        override val defaultTimeout get() = 5.seconds
    }

    @Serializable
    @SerialName("clickNode")
    data class ClickNode(
        val selector: NodeSelector,
        val longClick: Boolean = false,
        /** Must stay `true` in v1: clicking invisible nodes is not permitted. */
        val requireVisible: Boolean = true,
    ) : ActionParameters {
        override val concurrencyClass get() = ConcurrencyClass.UI
        override val needsAccessibility get() = true
        override val defaultTimeout get() = 10.seconds
    }

    @Serializable
    @SerialName("scrollNode")
    data class ScrollNode(
        /** `null` = first scrollable node in the active window. */
        val selector: NodeSelector? = null,
        val direction: ScrollDirection = ScrollDirection.FORWARD,
        val times: Int = 1,
    ) : ActionParameters {
        override val concurrencyClass get() = ConcurrencyClass.UI
        override val needsAccessibility get() = true
        override val defaultTimeout get() = 10.seconds
    }

    @Serializable
    @SerialName("enterText")
    data class EnterText(
        /** `null` = currently focused editable node. */
        val selector: NodeSelector? = null,
        val text: TextValue,
        /** Must be `true` when [text] is secure; sensitive text is never logged. */
        val sensitive: Boolean = false,
        val append: Boolean = false,
    ) : ActionParameters {
        override val concurrencyClass get() = ConcurrencyClass.UI
        override val needsAccessibility get() = true
        override val defaultTimeout get() = 10.seconds
    }

    @Serializable
    @SerialName("waitForNode")
    data class WaitForNode(
        val selector: NodeSelector,
        val state: NodeState = NodeState.PRESENT,
    ) : ActionParameters {
        override val concurrencyClass get() = ConcurrencyClass.UI
        override val needsAccessibility get() = true
        override val defaultTimeout get() = 15.seconds
    }

    @Serializable
    @SerialName("sendNotification")
    data class SendNotification(
        val title: TextValue,
        val text: TextValue,
        val tapOpensMacro: Boolean = true,
    ) : ActionParameters {
        override val concurrencyClass get() = ConcurrencyClass.BACKGROUND_SAFE
        override val needsAccessibility get() = false
        override val defaultTimeout get() = 5.seconds
    }

    @Serializable
    @SerialName("setVariable")
    data class SetVariable(
        val name: String,
        val value: VariableValue? = null,
        val expression: Expression? = null,
    ) : ActionParameters {
        override val concurrencyClass
            get() = if (expression is Expression.NodeText) ConcurrencyClass.UI else ConcurrencyClass.BACKGROUND_SAFE
        override val needsAccessibility get() = expression is Expression.NodeText
        override val defaultTimeout get() = 5.seconds
    }

    @Serializable
    @SerialName("if")
    data class If(
        val condition: Condition,
        val then: List<MacroStep>,
        val `else`: List<MacroStep> = emptyList(),
    ) : ActionParameters {
        override val concurrencyClass
            get() = (then + `else`).fold(
                if (condition.needsAccessibility) ConcurrencyClass.UI else ConcurrencyClass.BACKGROUND_SAFE,
            ) { acc, s -> acc max s.action.concurrencyClass }
        override val needsAccessibility
            get() = condition.needsAccessibility || (then + `else`).any { it.action.needsAccessibility }
        override val defaultTimeout get() = Duration.ZERO
        override val childGroups get() = listOf(then, `else`)
    }

    @Serializable
    @SerialName("repeat")
    data class Repeat(
        val count: Int? = null,
        val whileCondition: Condition? = null,
        val maxIterations: Int? = null,
        val body: List<MacroStep>,
        val delayBetween: Duration = Duration.ZERO,
    ) : ActionParameters {
        override val concurrencyClass
            get() = body.fold(
                if (whileCondition?.needsAccessibility == true) ConcurrencyClass.UI else ConcurrencyClass.BACKGROUND_SAFE,
            ) { acc, s -> acc max s.action.concurrencyClass }
        override val needsAccessibility
            get() = whileCondition?.needsAccessibility == true || body.any { it.action.needsAccessibility }
        override val defaultTimeout get() = Duration.ZERO
        override val childGroups get() = listOf(body)

        /** Upper bound of iterations for the static leaf-count check. */
        val iterationBound: Int get() = count ?: maxIterations ?: 0
    }

    @Serializable
    @SerialName("parallel")
    data class Parallel(
        val children: List<MacroStep>,
        val failFast: Boolean = true,
    ) : ActionParameters {
        override val concurrencyClass get() = ConcurrencyClass.BACKGROUND_SAFE
        override val needsAccessibility get() = children.any { it.action.needsAccessibility }
        override val defaultTimeout get() = Duration.ZERO
        override val childGroups get() = listOf(children)
    }

    @Serializable
    @SerialName("log")
    data class Log(
        val level: LogLevelParam = LogLevelParam.INFO,
        val message: TextValue,
    ) : ActionParameters {
        override val concurrencyClass get() = ConcurrencyClass.BACKGROUND_SAFE
        override val needsAccessibility get() = false
        override val defaultTimeout get() = 1.seconds
    }

    @Serializable
    @SerialName("stop")
    data class Stop(
        val success: Boolean = true,
        val message: String? = null,
    ) : ActionParameters {
        override val concurrencyClass get() = ConcurrencyClass.BACKGROUND_SAFE
        override val needsAccessibility get() = false
        override val defaultTimeout get() = 1.seconds
    }

    /** True for `if`/`repeat`/`parallel`. */
    val isContainer: Boolean get() = this is If || this is Repeat || this is Parallel
}

/** Stable wire name of the action type (the `type` discriminator). */
val ActionParameters.typeName: String
    get() = when (this) {
        is ActionParameters.LaunchApp -> "launchApp"
        is ActionParameters.OpenUrl -> "openUrl"
        is ActionParameters.Wait -> "wait"
        is ActionParameters.GlobalAction -> "globalAction"
        is ActionParameters.ClickNode -> "clickNode"
        is ActionParameters.ScrollNode -> "scrollNode"
        is ActionParameters.EnterText -> "enterText"
        is ActionParameters.WaitForNode -> "waitForNode"
        is ActionParameters.SendNotification -> "sendNotification"
        is ActionParameters.SetVariable -> "setVariable"
        is ActionParameters.If -> "if"
        is ActionParameters.Repeat -> "repeat"
        is ActionParameters.Parallel -> "parallel"
        is ActionParameters.Log -> "log"
        is ActionParameters.Stop -> "stop"
    }
