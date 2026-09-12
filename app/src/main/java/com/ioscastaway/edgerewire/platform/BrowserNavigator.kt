package com.ioscastaway.edgerewire.platform

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Finds the browser's own Back and Forward toolbar buttons in its accessibility tree.
 *
 * Why the toolbar and not a global back action: the button's enabled state is the only public
 * signal, from outside the app, of whether the current tab has history in that direction. A
 * disabled Back button means "this is the root of the tab", which is exactly when iOS does nothing
 * and Android would leave the app.
 *
 * Matching is by resource id first (stable across locales), then by content description
 * (English and Korean), because the ids are discovered at runtime and logged rather than assumed.
 */
class BrowserNavigator(private val log: (String) -> Unit) {

    enum class Direction { BACK, FORWARD }

    sealed interface Result {
        data object Clicked : Result
        data object Disabled : Result
        data object NotFound : Result
        data object ClickFailed : Result
    }

    data class Candidate(val id: String?, val desc: String?, val enabled: Boolean, val visible: Boolean, val clickable: Boolean) {
        override fun toString() = "id=${id?.substringAfter('/')} desc=$desc enabled=$enabled visible=$visible clickable=$clickable"
    }

    /** Best matching node for [direction], or null. Caller owns the returned node. */
    fun find(root: AccessibilityNodeInfo?, direction: Direction): AccessibilityNodeInfo? {
        root ?: return null
        val matches = ArrayList<AccessibilityNodeInfo>()
        walk(root, 0) { n -> if (matches(n, direction)) matches += n }
        if (matches.isEmpty()) return null
        // Prefer clickable, then visible; the toolbar button itself over a wrapper.
        return matches.maxByOrNull { (if (it.isClickable) 2 else 0) + (if (it.isVisibleToUser) 1 else 0) }
    }

    fun navigate(root: AccessibilityNodeInfo?, direction: Direction): Result {
        val node = find(root, direction) ?: return Result.NotFound
        if (!node.isEnabled) return Result.Disabled
        var target: AccessibilityNodeInfo? = node
        var hops = 0
        while (target != null && !target.isClickable && hops < 3) {
            target = target.parent
            hops++
        }
        val clicked = (target ?: node).performAction(AccessibilityNodeInfo.ACTION_CLICK)
        return if (clicked) Result.Clicked else Result.ClickFailed
    }

    /** Every node that looks like a navigation button, for the debug log. */
    fun candidates(root: AccessibilityNodeInfo?): List<Pair<Direction, Candidate>> {
        root ?: return emptyList()
        val out = ArrayList<Pair<Direction, Candidate>>()
        walk(root, 0) { n ->
            val dir = when {
                matches(n, Direction.BACK) -> Direction.BACK
                matches(n, Direction.FORWARD) -> Direction.FORWARD
                else -> null
            }
            if (dir != null) {
                out += dir to Candidate(
                    id = n.viewIdResourceName,
                    desc = n.contentDescription?.toString() ?: n.text?.toString(),
                    enabled = n.isEnabled,
                    visible = n.isVisibleToUser,
                    clickable = n.isClickable,
                )
            }
        }
        return out
    }

    private fun matches(n: AccessibilityNodeInfo, direction: Direction): Boolean {
        val id = n.viewIdResourceName?.substringAfter('/')?.lowercase() ?: ""
        val desc = (n.contentDescription?.toString() ?: n.text?.toString() ?: "").lowercase()
        return when (direction) {
            Direction.BACK ->
                (id.contains("back") && !id.contains("background") && !id.contains("backspace")) ||
                    desc == "back" || desc.startsWith("go back") || desc.startsWith("뒤로")
            Direction.FORWARD ->
                id.contains("forward") || desc == "forward" || desc.startsWith("go forward") || desc.startsWith("앞으로")
        }
    }

    private fun walk(node: AccessibilityNodeInfo, depth: Int, visit: (AccessibilityNodeInfo) -> Unit) {
        if (depth > MAX_DEPTH) return
        visit(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            walk(child, depth + 1, visit)
        }
    }

    private companion object {
        const val MAX_DEPTH = 40
    }
}
