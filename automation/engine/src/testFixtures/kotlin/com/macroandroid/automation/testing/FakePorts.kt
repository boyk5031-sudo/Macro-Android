package com.macroandroid.automation.testing

import com.macroandroid.automation.engine.EnginePorts
import com.macroandroid.automation.engine.ExecutionLogEntry
import com.macroandroid.automation.engine.ExecutionRecord
import com.macroandroid.automation.engine.StepAttemptRecord
import com.macroandroid.automation.model.ExecutionId
import com.macroandroid.automation.model.GlobalActionKind
import com.macroandroid.automation.model.Macro
import com.macroandroid.automation.model.MacroId
import com.macroandroid.automation.model.NodeSelector
import com.macroandroid.automation.model.ScrollDirection
import com.macroandroid.automation.model.TextMatch
import com.macroandroid.automation.port.AccessibilityGateway
import com.macroandroid.automation.port.AppLauncher
import com.macroandroid.automation.port.ExecutionLifecycleHooks
import com.macroandroid.automation.port.ExecutionStore
import com.macroandroid.automation.port.GateResult
import com.macroandroid.automation.port.MacroSource
import com.macroandroid.automation.port.NotificationPort
import com.macroandroid.automation.port.PreconditionGate
import com.macroandroid.automation.port.SecureValueResolver
import com.macroandroid.automation.port.UiNode
import com.macroandroid.core.common.error.AppResult
import com.macroandroid.core.common.error.ErrorCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/*
 * In-memory fakes for the engine ports. Used by JVM tests of the engine and by feature tests.
 * They live in testFixtures so production code can never depend on them.
 */

class FakeMacroSource(vararg macros: Macro) : MacroSource {
    val macros = macros.associateBy { it.id }.toMutableMap()
    override suspend fun getMacro(id: MacroId): Macro? = macros[id]
}

class InMemoryExecutionStore : ExecutionStore {
    val records = LinkedHashMap<ExecutionId, ExecutionRecord>()
    val attempts = ArrayList<StepAttemptRecord>()
    val logs = ArrayList<ExecutionLogEntry>()
    val history = ArrayList<ExecutionRecord>()

    override suspend fun insert(record: ExecutionRecord) {
        records[record.id] = record
        history += record
    }

    override suspend fun update(record: ExecutionRecord) {
        records[record.id] = record
        history += record
    }

    override suspend fun findByRunRequestId(runRequestId: String, since: Instant): ExecutionRecord? =
        records.values.firstOrNull { it.runRequestId == runRequestId && it.queuedAt >= since }

    override suspend fun insertStepAttempt(attempt: StepAttemptRecord) {
        attempts += attempt
    }

    override suspend fun updateStepAttempt(attempt: StepAttemptRecord) {
        val i = attempts.indexOfLast {
            it.executionId == attempt.executionId && it.stepId == attempt.stepId && it.attempt == attempt.attempt
        }
        if (i >= 0) attempts[i] = attempt else attempts += attempt
    }

    override suspend fun appendLogs(entries: List<ExecutionLogEntry>) {
        logs += entries
    }

    override suspend fun activeExecutions(): List<ExecutionRecord> = records.values.filter { it.state.isActive }

    fun statesOf(id: ExecutionId) = history.filter { it.id == id }.map { it.state }.distinctConsecutive()
}

private fun <T> List<T>.distinctConsecutive(): List<T> = fold(ArrayList()) { acc, t ->
    if (acc.lastOrNull() != t) acc += t
    acc
}

class FakeSecureValueResolver(private val values: MutableMap<String, String> = mutableMapOf()) : SecureValueResolver {
    operator fun set(id: String, value: String) {
        values[id] = value
    }

    override suspend fun resolve(id: String): AppResult<String> =
        values[id]?.let { AppResult.ok(it) } ?: AppResult.err(ErrorCode.SECURE_VALUE_UNAVAILABLE, id)
}

/** Gate whose result can be flipped from tests; `awaitPass` polls the state flow. */
class FakePreconditionGate(initial: GateResult = GateResult.Pass) : PreconditionGate {
    val state = MutableStateFlow(initial)
    var checks = 0

    override suspend fun checkUiSegment(needsAccessibility: Boolean): GateResult {
        checks++
        return state.value
    }

    override suspend fun awaitPass(needsAccessibility: Boolean, timeout: Duration): GateResult =
        withTimeoutOrNull(timeout) { state.first { it is GateResult.Pass } } ?: state.value
}

/** Simple screen model: a mutable list of nodes; actions record themselves. */
class FakeAccessibilityGateway : AccessibilityGateway {
    override var isConnected: Boolean = true
    var activePackageName: String? = null
    val nodes = ArrayList<UiNode>()
    val actions = ArrayList<String>()
    var failClicks = false
    var clickLatency: Duration = Duration.ZERO

    fun node(
        text: String? = null,
        viewId: String? = null,
        desc: String? = null,
        className: String? = null,
        pkg: String? = activePackageName,
        visible: Boolean = true,
        clickable: Boolean = true,
        editable: Boolean = false,
        scrollable: Boolean = false,
        password: Boolean = false,
        focused: Boolean = false,
    ): UiNode = UiNode(
        id = "n${nodes.size}", text = text, contentDescription = desc, className = className, viewId = viewId,
        packageName = pkg, isVisible = visible, isClickable = clickable, isLongClickable = clickable,
        isEditable = editable, isScrollable = scrollable, isPassword = password, isFocused = focused,
    ).also { nodes += it }

    override suspend fun activePackage(): String? = activePackageName

    override suspend fun findNodes(selector: NodeSelector): List<UiNode> = nodes.filter { n ->
        (selector.packageName == null || n.packageName == selector.packageName) &&
            (selector.viewId == null || n.viewId == selector.viewId) &&
            (selector.className == null || n.className == selector.className) &&
            (selector.text == null || matches(n.text, selector.text, selector.textMatch)) &&
            (selector.contentDescription == null || matches(n.contentDescription, selector.contentDescription, selector.textMatch))
    }

    private fun matches(actual: String?, expected: String, mode: TextMatch): Boolean {
        if (actual == null) return false
        return when (mode) {
            TextMatch.EQUALS -> actual == expected
            TextMatch.EQUALS_IGNORE_CASE -> actual.equals(expected, ignoreCase = true)
            TextMatch.CONTAINS -> actual.contains(expected, ignoreCase = true)
            TextMatch.REGEX -> Regex(expected).containsMatchIn(actual)
        }
    }

    override suspend fun focusedEditable(): UiNode? = nodes.firstOrNull { it.isEditable && it.isFocused }
    override suspend fun firstScrollable(packageName: String?): UiNode? =
        nodes.firstOrNull { it.isScrollable && (packageName == null || it.packageName == packageName) }

    override suspend fun click(node: UiNode, longClick: Boolean): AppResult<Unit> {
        if (clickLatency > Duration.ZERO) delay(clickLatency)
        actions += (if (longClick) "longClick:" else "click:") + (node.text ?: node.viewId ?: node.id)
        return if (failClicks) AppResult.err(ErrorCode.NODE_ACTION_REJECTED) else AppResult.ok(Unit)
    }

    override suspend fun setText(node: UiNode, text: String, append: Boolean): AppResult<Unit> {
        actions += "setText:${node.id}:$text"
        return AppResult.ok(Unit)
    }

    override suspend fun scroll(node: UiNode, direction: ScrollDirection): AppResult<Unit> {
        actions += "scroll:${node.id}:$direction"
        return AppResult.ok(Unit)
    }

    override suspend fun performGlobalAction(action: GlobalActionKind): AppResult<Unit> {
        actions += "global:$action"
        return AppResult.ok(Unit)
    }

    override suspend fun nodeText(node: UiNode): String? = node.text
}

class FakeAppLauncher(vararg installed: String) : AppLauncher {
    val installed = installed.toMutableSet()
    val launched = ArrayList<String>()
    val opened = ArrayList<String>()
    var windowAppears = true
    var launchLatency: Duration = 10.milliseconds

    override suspend fun isInstalled(packageName: String) = packageName in installed
    override suspend fun launch(packageName: String): AppResult<Unit> {
        launched += packageName
        return AppResult.ok(Unit)
    }

    override suspend fun openUrl(url: String, preferPackage: String?): AppResult<Unit> {
        opened += url
        return AppResult.ok(Unit)
    }

    override suspend fun awaitWindow(packageName: String, timeout: Duration): Boolean {
        delay(launchLatency)
        return windowAppears
    }
}

class FakeNotificationPort : NotificationPort {
    val posted = ArrayList<Pair<String, String>>()
    override suspend fun postMacroNotification(
        executionId: ExecutionId,
        title: String,
        text: String,
        tapOpensExecution: Boolean,
    ): AppResult<Unit> {
        posted += title to text
        return AppResult.ok(Unit)
    }
}

class RecordingHooks : ExecutionLifecycleHooks {
    val events = ArrayList<String>()
    override suspend fun onRunningStarted(record: ExecutionRecord, macro: Macro) { events += "running:${record.id}" }
    override suspend fun onBlocked(record: ExecutionRecord, macro: Macro) { events += "blocked:${record.blockedReason?.code}" }
    override suspend fun onTerminal(record: ExecutionRecord, macro: Macro?) { events += "terminal:${record.state}" }
}

/** Bundle with sensible defaults; override individual fakes via named args. */
fun fakePorts(
    macroSource: FakeMacroSource = FakeMacroSource(),
    store: InMemoryExecutionStore = InMemoryExecutionStore(),
    secure: FakeSecureValueResolver = FakeSecureValueResolver(),
    gate: FakePreconditionGate = FakePreconditionGate(),
    accessibility: FakeAccessibilityGateway = FakeAccessibilityGateway(),
    launcher: FakeAppLauncher = FakeAppLauncher(),
    notifications: FakeNotificationPort = FakeNotificationPort(),
    hooks: ExecutionLifecycleHooks = RecordingHooks(),
) = EnginePorts(macroSource, store, secure, gate, accessibility, launcher, notifications, hooks)
