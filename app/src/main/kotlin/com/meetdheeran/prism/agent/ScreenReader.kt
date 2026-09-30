package com.meetdheeran.prism.agent

import android.content.Context
import android.graphics.Rect
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo

/** One thing on screen the agent can see or act on. Positions are 0–1000 of the screen width/height. */
class UiElement(
    val id: Int,
    val node: AccessibilityNodeInfo,
    val role: String,
    val label: String,
    val value: String?,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val password: Boolean,
    val flags: List<String>,
    val bounds: Rect,
    val x: Int,
    val y: Int,
    val viewId: String?,
) {
    val actionable: Boolean get() = clickable || editable || scrollable

    fun line(): String = buildString {
        append('[').append(id).append("] ").append(role)
        if (password) append(" (password field — never type here)")
        else if (label.isNotEmpty()) append(" \"").append(label).append('"')
        if (!password && !value.isNullOrEmpty()) append(" = \"").append(value).append('"')
        if (flags.isNotEmpty()) append(" (").append(flags.joinToString()).append(')')
        append(" @").append(x).append(',').append(y)
    }
}

/** A read of the app in front: what it is and its elements, numbered for the model. */
class Screen(
    val packageName: String,
    val appLabel: String,
    val elements: List<UiElement>,
    val widthPx: Int,
    val heightPx: Int,
) {
    val readable: Boolean get() = elements.isNotEmpty()
    val actionableCount: Int get() = elements.count { it.actionable }
    operator fun get(id: Int): UiElement? = elements.firstOrNull { it.id == id }

    /** Cheap fingerprint to notice "nothing changed" between steps. */
    val signature: String get() = packageName + "|" + elements.take(60).joinToString("|") { it.role + it.label + (it.value ?: "") }.hashCode()

    fun describe(): String = buildString {
        append("App: ").append(appLabel).append(" (").append(packageName).append(")\n")
        if (elements.isEmpty()) append("(Nothing readable on this screen — it may be loading, secure, or drawn without accessibility info.)\n")
        elements.forEach { append(it.line()).append('\n') }
    }
}

/**
 * Turns the accessibility tree of the app in front into a short numbered list the model can act
 * on. Keeps things you can press / type into / scroll, plus visible text for context. Text that is
 * already part of a button's label isn't repeated. Password fields are listed but never read.
 */
object ScreenReader {
    private const val MAX_ELEMENTS = 170
    private const val MAX_LABEL = 90

    fun read(svc: AgentAccessibilityService, ctx: Context): Screen {
        val size = screenSize(ctx)
        val root = svc.activeRoot() ?: return Screen("", "unknown", emptyList(), size.width(), size.height())
        val pkg = root.packageName?.toString().orEmpty()
        val label = runCatching {
            ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)

        val out = ArrayList<UiElement>()
        var nextId = 1
        fun norm(v: Int, total: Int) = if (total <= 0) 0 else (v * 1000 / total).coerceIn(0, 1000)

        fun walk(n: AccessibilityNodeInfo, ancestorLabel: String?, depth: Int) {
            if (depth > 40 || !n.isVisibleToUser) return
            val b = Rect().also { n.getBoundsInScreen(it) }
            if (b.width() <= 1 || b.height() <= 1 || b.bottom < 0 || b.top > size.height() || b.right < 0 || b.left > size.width()) {
                // Off-screen containers can still hold on-screen children (e.g. a big list).
                for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, ancestorLabel, depth + 1) }
                return
            }
            val password = n.isPassword
            val text = if (password) "" else n.text?.toString()?.trim().orEmpty()
            val desc = n.contentDescription?.toString()?.trim().orEmpty()
            val hint = n.hintText?.toString()?.trim().orEmpty()
            val viewId = n.viewIdResourceName?.substringAfter(":id/")
            val editable = n.isEditable
            val clickable = n.isClickable || n.isLongClickable || n.isCheckable
            val scrollable = n.isScrollable
            val cls = n.className?.toString().orEmpty()

            var ownLabel = when {
                editable -> hint.ifEmpty { desc }
                text.isNotEmpty() -> text
                else -> desc
            }
            if (ownLabel.isEmpty() && (clickable || editable)) ownLabel = childText(n, 3)
            if (ownLabel.isEmpty() && (clickable || editable) && viewId != null) ownLabel = viewId.replace('_', ' ')
            ownLabel = ownLabel.replace('\n', ' ').take(MAX_LABEL)

            val keep = when {
                editable || clickable || scrollable -> true
                ownLabel.isEmpty() -> false
                // Plain text already spoken for by the button around it.
                ancestorLabel != null && ancestorLabel.contains(ownLabel) -> false
                else -> true
            }
            if (keep && out.size < MAX_ELEMENTS * 2) {
                val flags = buildList {
                    if (n.isCheckable) add(if (n.isChecked) "on" else "off")
                    if (n.isSelected) add("selected")
                    if (n.isFocused) add("focused")
                    if (scrollable) add("scrollable")
                    if (!n.isEnabled) add("disabled")
                }
                val role = when {
                    editable -> "input"
                    cls.endsWith("Switch") -> "switch"
                    cls.endsWith("CheckBox") -> "checkbox"
                    cls.endsWith("RadioButton") -> "radio"
                    clickable -> if (cls.endsWith("ImageButton") || cls.endsWith("ImageView")) "icon-button" else "button"
                    scrollable -> "list"
                    cls.endsWith("ImageView") -> "image"
                    else -> "text"
                }
                out += UiElement(
                    id = nextId++, node = n, role = role, label = ownLabel,
                    value = if (editable && !password) text.takeIf { it.isNotEmpty() && it != hint }?.take(MAX_LABEL) else null,
                    clickable = clickable, editable = editable, scrollable = scrollable, password = password,
                    flags = flags, bounds = b, x = norm(b.centerX(), size.width()), y = norm(b.centerY(), size.height()), viewId = viewId,
                )
            }
            val passDown = if (clickable && ownLabel.isNotEmpty()) ownLabel else ancestorLabel
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, passDown, depth + 1) }
        }

        runCatching { walk(root, null, 0) }
        // Over budget: keep everything actionable, drop plain text from the end.
        val trimmed = if (out.size <= MAX_ELEMENTS) out else {
            val actionable = out.filter { it.actionable }
            val texts = out.filter { !it.actionable }.take((MAX_ELEMENTS - actionable.size).coerceAtLeast(0))
            (actionable + texts).sortedBy { it.id }
        }
        return Screen(pkg, label, trimmed, size.width(), size.height())
    }

    /** Up to [max] bits of text under a node, for buttons that are just a container around labels. */
    private fun childText(n: AccessibilityNodeInfo, max: Int): String {
        val parts = ArrayList<String>()
        fun walk(c: AccessibilityNodeInfo, depth: Int) {
            if (parts.size >= max || depth > 6) return
            val t = (c.text ?: c.contentDescription)?.toString()?.trim()
            if (!t.isNullOrEmpty() && !c.isPassword) parts += t
            for (i in 0 until c.childCount) c.getChild(i)?.let { walk(it, depth + 1) }
        }
        for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, 1) }
        return parts.joinToString(" · ")
    }

    fun screenSize(ctx: Context): Rect =
        runCatching { ctx.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds }.getOrNull()
            ?: Rect(0, 0, ctx.resources.displayMetrics.widthPixels, ctx.resources.displayMetrics.heightPixels)
}
