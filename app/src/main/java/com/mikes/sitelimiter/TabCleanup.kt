package com.mikes.sitelimiter

/** A short-lived target, held only in the accessibility service's memory. */
internal data class BlockedTab(
    val browser: String,
    val host: String,
    val windowId: Int,
    val address: String?,
)

internal enum class TabAction { OPEN_TABS, CLOSE_TAB, DISMISS_TABS, FOCUS_ADDRESS, SET_BLANK, SUBMIT_BLANK }
internal enum class CleanupResult { CLOSED, BLANKED, CANCELLED, FAILED }

internal data class TabObservation(
    val browser: String?,
    val windowId: Int = -1,
    val address: String? = null,
    val editing: Boolean = false,
    val tabCount: Int? = null,
    val canClose: Boolean = false,
    val tabsVisible: Boolean = false,
) {
    val host: String? get() = Browsers.hostFromBarText(address)
}

/** Platform actions operate on the same fresh tree that supplied [observation]. */
internal interface TabCleanupUi {
    val observation: TabObservation
    fun perform(action: TabAction): Boolean
}

/**
 * No sleeps, retained accessibility nodes, or retries of destructive actions. Once a close
 * has been accepted, only observe: a second click or blanking could affect the next tab.
 */
internal class TabCleanup(val target: BlockedTab, private val startedAt: Long) {
    private enum class Step { WAIT_BROWSER, WAIT_TABS, RETURN_TO_PAGE, FOCUS, EDIT, VERIFY_BLANK, VERIFY_CLOSE }
    private var step = Step.WAIT_BROWSER
    private var stepAt = startedAt
    private var originalCount: Int? = null
    var result: CleanupResult? = null
        private set

    fun advance(ui: TabCleanupUi, now: Long, stillBlocked: Boolean, blockScreenPackage: String) {
        if (result != null) return
        if (!stillBlocked) return finish(CleanupResult.CANCELLED)
        val page = ui.observation
        if (page.browser != null && page.browser != target.browser) {
            // Allow the block activity's finishing animation, but never inspect or operate
            // on another app. Going elsewhere cancels without pulling the user home.
            if (step == Step.WAIT_BROWSER && page.browser == blockScreenPackage && now - startedAt < 1_000) return
            return finish(CleanupResult.CANCELLED)
        }
        if (now - startedAt >= TIMEOUT_MS) return finish(CleanupResult.FAILED)
        if (page.browser == null) return

        when (step) {
            Step.WAIT_BROWSER -> {
                if (page.windowId != target.windowId || page.editing) return finish(CleanupResult.CANCELLED)
                if (page.address == null) return // Hidden toolbar: never act on a stale host.
                if (!matches(page)) return finish(CleanupResult.CANCELLED)
                originalCount = page.tabCount
                if (ui.perform(TabAction.OPEN_TABS)) move(Step.WAIT_TABS, now)
                else focus(ui, now)
            }
            Step.WAIT_TABS -> {
                if (page.editing || (page.address != null && !matches(page))) return finish(CleanupResult.CANCELLED)
                if (page.canClose) {
                    if (ui.perform(TabAction.CLOSE_TAB)) move(Step.VERIFY_CLOSE, now)
                    else returnToPage(ui, now)
                } else if (now - stepAt >= 900) {
                    // BACK is allowed only for a browser tab UI we actually opened.
                    // Otherwise it could navigate the web page or close another tab.
                    if (page.tabsVisible) returnToPage(ui, now)
                    else if (page.windowId == target.windowId && matches(page)) focus(ui, now)
                    else finish(CleanupResult.FAILED)
                }
            }
            Step.RETURN_TO_PAGE -> {
                if (page.tabsVisible || page.address == null) return
                if (page.windowId != target.windowId || page.editing || !matches(page)) return finish(CleanupResult.CANCELLED)
                focus(ui, now)
            }
            Step.FOCUS -> {
                if (page.windowId != target.windowId) return finish(CleanupResult.CANCELLED)
                if (page.address == null) return
                // Focusing often expands an elided address to the full URL.
                if (!matchesExpandedAddress(page)) return finish(CleanupResult.CANCELLED)
                if (!page.editing) return
                if (ui.perform(TabAction.SET_BLANK)) move(Step.EDIT, now)
                else finish(CleanupResult.FAILED)
            }
            Step.EDIT -> {
                if (page.windowId != target.windowId) return finish(CleanupResult.CANCELLED)
                if (!page.editing) return finish(CleanupResult.CANCELLED)
                if (page.address != BLANK_URL) {
                    // SET_TEXT may be acknowledged before the new tree reaches us.
                    if (now - stepAt < 400 && page.host == target.host) return
                    return finish(CleanupResult.CANCELLED)
                }
                if (ui.perform(TabAction.SUBMIT_BLANK)) move(Step.VERIFY_BLANK, now)
                else finish(CleanupResult.FAILED)
            }
            Step.VERIFY_BLANK -> {
                // Typed text alone is not navigation. The browser must leave edit mode.
                if (!page.editing && page.address == BLANK_URL) finish(CleanupResult.BLANKED)
            }
            Step.VERIFY_CLOSE -> {
                val countDecreased = originalCount?.let { page.tabCount == it - 1 } == true
                val navigated = !page.editing && page.address != null && page.address != target.address && !matches(page)
                if (countDecreased || navigated) finish(CleanupResult.CLOSED)
            }
        }
    }

    private fun matches(page: TabObservation): Boolean = page.host == target.host &&
        (target.address == null || page.address == target.address)

    private fun matchesExpandedAddress(page: TabObservation): Boolean {
        if (page.host != target.host) return false
        val original = target.address?.let(::withoutScheme) ?: return true
        // A hostname-only display cannot identify a hidden path. When a path was
        // visible, however, do not overwrite a different page on the same domain.
        return original == target.host || page.address?.let(::withoutScheme) == original
    }

    private fun withoutScheme(address: String) = address.substringAfter("://").removePrefix("www.").trimEnd('/')

    private fun focus(ui: TabCleanupUi, now: Long) {
        if (ui.perform(TabAction.FOCUS_ADDRESS)) move(Step.FOCUS, now)
        else finish(CleanupResult.FAILED)
    }

    private fun returnToPage(ui: TabCleanupUi, now: Long) {
        if (ui.observation.tabsVisible && ui.perform(TabAction.DISMISS_TABS)) move(Step.RETURN_TO_PAGE, now)
        else finish(CleanupResult.FAILED)
    }

    private fun move(next: Step, now: Long) { step = next; stepAt = now }
    private fun finish(outcome: CleanupResult) { result = outcome }

    companion object {
        const val TIMEOUT_MS = 5_000L
        const val BLANK_URL = "about:blank"
    }
}
