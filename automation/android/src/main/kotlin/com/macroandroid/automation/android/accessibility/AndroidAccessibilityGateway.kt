package com.macroandroid.automation.android.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.macroandroid.automation.model.GlobalActionKind
import com.macroandroid.automation.model.NodeSelector
import com.macroandroid.automation.model.ScrollDirection
import com.macroandroid.automation.model.TextMatch
import com.macroandroid.automation.port.AccessibilityGateway
import com.macroandroid.automation.port.UiNode
import com.macroandroid.core.common.coroutines.AppDispatchers
import com.macroandroid.core.common.error.AppResult
import com.macroandroid.core.common.error.ErrorCode
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real [AccessibilityGateway] over [AccessibilityNodeInfo].
 *
 * Safety rules enforced here (not only in the engine):
 *  - password nodes are never returned as actionable and never read;
 *  - the closed set of actions is CLICK, LONG_CLICK, SET_TEXT, SCROLL_FORWARD/BACKWARD and five global actions;
 *  - node handles expire after one lookup round (`nodeCache`), so a stale reference can't act on a new screen.
 */
@Singleton
class AndroidAccessibilityGateway @Inject constructor(
    private val registry: AccessibilityServiceRegistry,
    private val dispatchers: AppDispatchers,
) : AccessibilityGateway {

    private val nodeCache = ConcurrentHashMap<String, AccessibilityNodeInfo>()
    private val handleCounter = AtomicLong()

    override val isConnected: Boolean get() = registry.isConnected

    private fun service(): AccessibilityService? = registry.service.value

    override suspend fun activePackage(): String? = withContext(dispatchers.main) {
        service()?.rootInActiveWindow?.packageName?.toString()
    }

    override suspend fun findNodes(selector: NodeSelector): List<UiNode> = withContext(dispatchers.main) {
        val service = service() ?: return@withContext emptyList()
        recycleCache()
        val roots = buildList {
            service.rootInActiveWindow?.let(::add)
            // Also search other visible windows (e.g. dialogs, IME excluded) when the package matches.
            service.windows.forEach { w ->
                val root = w.root ?: return@forEach
                if (none { it == root }) add(root)
            }
        }
        val matches = ArrayList<AccessibilityNodeInfo>()
        roots.forEach { root -> collect(root, selector, matches, depth = 0) }
        matches.mapNotNull { node ->
            val target = if (selector.clickableAncestor) clickableAncestorOrSelf(node) else node
            toUiNode(target)
        }.distinctBy { it.id }
    }

    override suspend fun focusedEditable(): UiNode? = withContext(dispatchers.main) {
        val service = service() ?: return@withContext null
        val focused = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return@withContext null
        if (!focused.isEditable || focused.isPassword) return@withContext null
        toUiNode(focused)
    }

    override suspend fun firstScrollable(packageName: String?): UiNode? = withContext(dispatchers.main) {
        val root = service()?.rootInActiveWindow ?: return@withContext null
        if (packageName != null && root.packageName?.toString() != packageName) return@withContext null
        findFirst(root) { it.isScrollable && it.isVisibleToUser }?.let(::toUiNode)
    }

    override suspend fun click(node: UiNode, longClick: Boolean): AppResult<Unit> = perform(node) { info ->
        if (info.isPassword) return@perform AppResult.err(ErrorCode.NODE_IS_PASSWORD)
        val action = if (longClick) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        if (info.performAction(action)) AppResult.ok(Unit) else AppResult.err(ErrorCode.NODE_ACTION_REJECTED, "click")
    }

    override suspend fun setText(node: UiNode, text: String, append: Boolean): AppResult<Unit> = perform(node) { info ->
        if (info.isPassword) return@perform AppResult.err(ErrorCode.NODE_IS_PASSWORD)
        if (!info.isEditable) return@perform AppResult.err(ErrorCode.NODE_NOT_EDITABLE)
        val value = if (append) (info.text?.toString().orEmpty() + text) else text
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        info.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        if (info.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            AppResult.ok(Unit)
        } else {
            AppResult.err(ErrorCode.NODE_ACTION_REJECTED, "setText")
        }
    }

    override suspend fun scroll(node: UiNode, direction: ScrollDirection): AppResult<Unit> = perform(node) { info ->
        if (!info.isScrollable) return@perform AppResult.err(ErrorCode.NODE_NOT_SCROLLABLE)
        val action = when (direction) {
            ScrollDirection.FORWARD -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            ScrollDirection.BACKWARD -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        if (info.performAction(action)) AppResult.ok(Unit) else AppResult.err(ErrorCode.NODE_ACTION_REJECTED, "scroll")
    }

    override suspend fun performGlobalAction(action: GlobalActionKind): AppResult<Unit> = withContext(dispatchers.main) {
        val service = service() ?: return@withContext AppResult.err(ErrorCode.A11Y_SERVICE_DISCONNECTED)
        val id = when (action) {
            GlobalActionKind.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
            GlobalActionKind.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
            GlobalActionKind.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
            GlobalActionKind.NOTIFICATIONS -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            GlobalActionKind.QUICK_SETTINGS -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
        }
        if (service.performGlobalAction(id)) AppResult.ok(Unit) else AppResult.err(ErrorCode.GLOBAL_ACTION_FAILED, action.name)
    }

    override suspend fun nodeText(node: UiNode): String? = withContext(dispatchers.main) {
        val info = nodeCache[node.id] ?: return@withContext null
        if (info.isPassword) return@withContext null
        info.refresh()
        info.text?.toString() ?: info.contentDescription?.toString()
    }

    // ---- internals -----------------------------------------------------------------------------

    private suspend fun perform(node: UiNode, block: (AccessibilityNodeInfo) -> AppResult<Unit>): AppResult<Unit> =
        withContext(dispatchers.main) {
            if (service() == null) return@withContext AppResult.err(ErrorCode.A11Y_SERVICE_DISCONNECTED)
            val info = nodeCache[node.id] ?: return@withContext AppResult.err(ErrorCode.NODE_STALE)
            if (!info.refresh()) return@withContext AppResult.err(ErrorCode.NODE_STALE)
            block(info)
        }

    private fun collect(node: AccessibilityNodeInfo, selector: NodeSelector, out: MutableList<AccessibilityNodeInfo>, depth: Int) {
        if (depth > MAX_DEPTH) return
        if (matches(node, selector)) out += node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collect(child, selector, out, depth + 1)
        }
    }

    private fun findFirst(node: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findFirst(child, predicate)?.let { return it }
        }
        return null
    }

    private fun matches(node: AccessibilityNodeInfo, s: NodeSelector): Boolean {
        if (s.packageName != null && node.packageName?.toString() != s.packageName) return false
        if (s.viewId != null && !idMatches(node.viewIdResourceName, s.viewId!!)) return false
        if (s.className != null && node.className?.toString() != s.className) return false
        if (s.text != null && !textMatches(node.text?.toString(), s.text!!, s.textMatch)) return false
        if (s.contentDescription != null &&
            !textMatches(node.contentDescription?.toString(), s.contentDescription!!, s.textMatch)
        ) {
            return false
        }
        return true
    }

    private fun idMatches(actual: String?, expected: String): Boolean {
        if (actual == null) return false
        // Accept both "com.pkg:id/name" and bare "name".
        return actual == expected || actual.substringAfter(":id/", actual) == expected
    }

    private fun textMatches(actual: String?, expected: String, mode: TextMatch): Boolean {
        if (actual == null) return false
        return when (mode) {
            TextMatch.EQUALS -> actual == expected
            TextMatch.EQUALS_IGNORE_CASE -> actual.equals(expected, ignoreCase = true)
            TextMatch.CONTAINS -> actual.contains(expected, ignoreCase = true)
            TextMatch.REGEX -> runCatching { Regex(expected).containsMatchIn(actual) }.getOrDefault(false)
        }
    }

    private fun clickableAncestorOrSelf(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        if (node.isClickable || node.isLongClickable || node.isEditable || node.isScrollable) return node
        var current = node.parent
        var hops = 0
        while (current != null && hops < MAX_ANCESTOR_HOPS) {
            if (current.isClickable || current.isLongClickable) return current
            current = current.parent
            hops++
        }
        return node
    }

    private fun toUiNode(info: AccessibilityNodeInfo): UiNode {
        val id = "n${handleCounter.incrementAndGet()}"
        nodeCache[id] = info
        val bounds = Rect().also(info::getBoundsInScreen)
        return UiNode(
            id = id,
            text = if (info.isPassword) null else info.text?.toString(),
            contentDescription = info.contentDescription?.toString(),
            className = info.className?.toString(),
            viewId = info.viewIdResourceName,
            packageName = info.packageName?.toString(),
            isVisible = info.isVisibleToUser && !bounds.isEmpty,
            isClickable = info.isClickable,
            isLongClickable = info.isLongClickable,
            isEditable = info.isEditable,
            isScrollable = info.isScrollable,
            isPassword = info.isPassword,
            isFocused = info.isFocused,
        )
    }

    private fun recycleCache() {
        if (nodeCache.size > CACHE_LIMIT) nodeCache.clear()
    }

    private companion object {
        const val MAX_DEPTH = 64
        const val MAX_ANCESTOR_HOPS = 6
        const val CACHE_LIMIT = 512
    }
}
