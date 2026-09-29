package com.mikes.sitelimiter

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/** A single sample owns its nodes; none survive an asynchronous browser transition. */
internal class BrowserTabUi(
    root: AccessibilityNodeInfo?,
    private val targetPackage: String,
    private val profile: TabControlsProfile,
    closeLabels: Set<String>,
    newTabLabels: Set<String>,
    private val back: () -> Boolean,
) : TabCleanupUi, AutoCloseable {
    private val owned = mutableListOf<AccessibilityNodeInfo>()
    private val native = mutableListOf<AccessibilityNodeInfo>()
    private val controls = mutableListOf<TabControl>()
    private val selection: BrowserTabControls
    private val addressIndex: Int?
    override val observation: TabObservation

    init {
        val pkg = root?.packageName?.toString()
        try {
            if (root != null && pkg == targetPackage) readNativeChrome(root)
        } catch (t: Throwable) {
            close()
            throw t
        }
        selection = BrowserTabControls(controls, profile, closeLabels, newTabLabels)
        val addressIds = Browsers.urlBarIds(targetPackage).map { it.substringAfter(":id/") }.toSet() +
            profile.editIds + Browsers.URL_BAR_ID_SUFFIXES.map { it.substringAfter(":id/") }
        val bars = controls.indices.filter { controls[it].id in addressIds }
        addressIndex = bars.filter { controls[it].focused && controls[it].editable }.singleOrNull()
            ?: bars.singleOrNull()
        val bar = addressIndex?.let { controls[it] }
        observation = TabObservation(
            browser = pkg,
            windowId = root?.windowId ?: -1,
            address = bar?.let { it.text.ifBlank { it.description }.trim() },
            editing = bar?.focused == true,
            tabCount = selection.count(),
            canClose = selection.close() != null,
            tabsVisible = selection.tabsVisible(),
        )
    }

    override fun perform(action: TabAction): Boolean = when (action) {
        TabAction.OPEN_TABS -> selection.button()?.let {
            act(it, if (profile.longPress) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK)
        } ?: false
        TabAction.CLOSE_TAB -> selection.close()?.let { act(it, AccessibilityNodeInfo.ACTION_CLICK) } ?: false
        TabAction.DISMISS_TABS -> observation.tabsVisible && back()
        TabAction.FOCUS_ADDRESS -> addressIndex?.let { clickAddress(it) } ?: false
        TabAction.SET_BLANK -> addressIndex?.let {
            controls[it].focused && controls[it].editable && native[it].performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, TabCleanup.BLANK_URL) },
            )
        } ?: false
        TabAction.SUBMIT_BLANK -> submitBlank()
    }

    private fun submitBlank(): Boolean {
        val index = addressIndex ?: return false
        if (!controls[index].focused || controls[index].text.trim() != TabCleanup.BLANK_URL) return false
        val bar = native[index]
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && bar.actionList.any {
                it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
            }) {
            return bar.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        val go = controls.indices.filter { controls[it].id in profile.submitIds && controls[it].clickable }.singleOrNull()
        if (go != null) return act(go, AccessibilityNodeInfo.ACTION_CLICK)

        // On older Android, Chromium exposes the literal URL as an omnibox suggestion.
        // Restrict to its native suggestion row and the exact constant we just entered.
        val suggestion = controls.indices.filter { i ->
            controls[i].text == TabCleanup.BLANK_URL && controls[i].id in setOf("line_1", "suggestion_text")
        }.singleOrNull() ?: return false
        var parent = controls[suggestion].parent
        repeat(3) {
            if (parent !in controls.indices) return false
            if (controls[parent].id in setOf("omnibox_suggestion_row", "suggestion_view") && controls[parent].clickable) {
                return act(parent, AccessibilityNodeInfo.ACTION_CLICK)
            }
            parent = controls[parent].parent
        }
        return false
    }

    private fun clickAddress(index: Int): Boolean {
        val focusControl = controls.indices.filter {
            controls[it].id in profile.focusIds && controls[it].clickable
        }.singleOrNull()
        if (focusControl != null) return act(focusControl, AccessibilityNodeInfo.ACTION_CLICK)
        var current = index
        repeat(3) {
            if (current !in controls.indices) return false
            if (controls[current].clickable) return act(current, AccessibilityNodeInfo.ACTION_CLICK)
            current = controls[current].parent
        }
        return false
    }

    private fun act(index: Int, action: Int) = controls[index].enabled && native[index].performAction(action)

    private fun readNativeChrome(root: AccessibilityNodeInfo) {
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to -1)
        while (queue.isNotEmpty() && native.size < MAX_NODES) {
            val (node, parent) = queue.removeFirst()
            // Web content can mimic labels and resource IDs. Do not even read its text.
            val className = node.className?.toString().orEmpty()
            if (className.contains("WebView", ignoreCase = true) || className.contains("GeckoView")) continue
            if (node.packageName?.toString() != targetPackage) continue
            val index = controls.size
            native.add(node)
            val visible = node.isVisibleToUser
            val resourceId = node.viewIdResourceName.orEmpty()
            controls.add(TabControl(
                id = if (visible && resourceId.startsWith("$targetPackage:id/")) resourceId.substringAfter(":id/") else "",
                className = className,
                text = if (visible) node.text?.toString().orEmpty() else "",
                description = if (visible) node.contentDescription?.toString().orEmpty() else "",
                parent = parent,
                selected = visible && node.isSelected,
                focused = visible && node.isFocused,
                editable = visible && node.isEditable,
                clickable = visible && node.isClickable,
                longClickable = visible && node.isLongClickable,
                enabled = visible && node.isEnabled,
            ))
            for (i in 0 until node.childCount) {
                if (owned.size >= MAX_NODES - 1) break
                val child = node.getChild(i) ?: continue
                owned.add(child)
                queue.add(child to index)
            }
        }
    }

    override fun close() {
        owned.forEach(::recycleTabNode)
        owned.clear()
    }

    companion object {
        private const val MAX_NODES = 400

        /** Resolve localized labels rather than assuming the browser UI is English. */
        fun labels(context: Context, pkg: String, names: Set<String>): Set<String> = runCatching {
            val resources = context.packageManager.getResourcesForApplication(pkg)
            names.mapNotNull { name ->
                val id = resources.getIdentifier(name, "string", pkg)
                if (id == 0) null else resources.getString(id).takeIf { it.isNotBlank() }
            }.toSet()
        }.getOrDefault(emptySet())
    }
}

@Suppress("DEPRECATION")
internal fun recycleTabNode(node: AccessibilityNodeInfo) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) runCatching { node.recycle() }
}
