package com.mikes.sitelimiter

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class TabCleanupTest {
    private val target = BlockedTab("com.brave.browser", "example.com", 7, "example.com")
    private val page = TabObservation(target.browser, 7, "example.com", tabCount = 2)
    private val wall = "com.mikes.sitelimiter"

    private class Ui(override var observation: TabObservation) : TabCleanupUi {
        val actions = mutableListOf<TabAction>()
        var rejected = emptySet<TabAction>()
        override fun perform(action: TabAction): Boolean {
            actions += action
            return action !in rejected
        }
    }

    @Test fun `zero minute hard limit closes one tab even when the next tab has the same host`() {
        val now = Instant.parse("2026-09-29T12:00:00Z").toEpochMilli()
        val rules = LimitPolicy.refresh(LimitState(rules = listOf(Rule(target.host, 0, RuleMode.HARD))), now, ZoneId.of("UTC"))
        val blocked = LimitPolicy.blockingRule(rules, target.host, now) != null
        val cleanup = TabCleanup(target, 0)
        val ui = Ui(page)
        cleanup.advance(ui, 0, blocked, wall)
        ui.observation = page.copy(address = null, windowId = 8, tabsVisible = true, canClose = true)
        cleanup.advance(ui, 100, blocked, wall)
        assertNull(cleanup.result) // A successful click is not proof of closure.
        cleanup.advance(ui, 200, blocked, wall)
        ui.observation = page.copy(tabCount = 1)
        cleanup.advance(ui, 300, blocked, wall)
        cleanup.advance(ui, 400, blocked, wall)
        assertEquals(CleanupResult.CLOSED, cleanup.result)
        assertEquals(listOf(TabAction.OPEN_TABS, TabAction.CLOSE_TAB), ui.actions)
    }

    @Test fun `last tab closing to the browser start page is confirmed`() {
        val cleanup = TabCleanup(target, 0)
        val ui = Ui(page.copy(tabCount = 1))
        cleanup.advance(ui, 0, true, wall)
        ui.observation = page.copy(canClose = true, tabsVisible = true)
        cleanup.advance(ui, 100, true, wall)
        ui.observation = page.copy(address = "", tabCount = 0)
        cleanup.advance(ui, 200, true, wall)
        assertEquals(CleanupResult.CLOSED, cleanup.result)
    }

    @Test fun `an unconfirmed close never retries or blanks the next tab`() {
        val cleanup = TabCleanup(target, 0)
        val ui = Ui(page)
        cleanup.advance(ui, 0, true, wall)
        ui.observation = page.copy(canClose = true, tabsVisible = true)
        cleanup.advance(ui, 100, true, wall)
        for (time in 200L..5_000L step 100) cleanup.advance(ui, time, true, wall)
        assertEquals(CleanupResult.FAILED, cleanup.result)
        assertEquals(listOf(TabAction.OPEN_TABS, TabAction.CLOSE_TAB), ui.actions)
    }

    @Test fun `missing close controls dismiss the overview before blanking the same tab`() {
        val cleanup = TabCleanup(target, 0)
        val ui = Ui(page)
        cleanup.advance(ui, 0, true, wall)
        ui.observation = page.copy(address = null, tabsVisible = true)
        cleanup.advance(ui, 1_000, true, wall)
        ui.observation = page
        cleanup.advance(ui, 1_100, true, wall)
        ui.observation = page.copy(address = "https://example.com/", editing = true)
        cleanup.advance(ui, 1_200, true, wall)
        cleanup.advance(ui, 1_300, true, wall) // Old tree after SET_TEXT is harmless.
        ui.observation = page.copy(address = "about:blank", editing = true)
        cleanup.advance(ui, 1_400, true, wall)
        cleanup.advance(ui, 1_500, true, wall)
        assertNull(cleanup.result) // An address still being edited is not a blank page.
        ui.observation = page.copy(address = "about:blank")
        cleanup.advance(ui, 1_600, true, wall)
        assertEquals(CleanupResult.BLANKED, cleanup.result)
        assertEquals(listOf(TabAction.OPEN_TABS, TabAction.DISMISS_TABS, TabAction.FOCUS_ADDRESS,
            TabAction.SET_BLANK, TabAction.SUBMIT_BLANK), ui.actions)
    }

    @Test fun `unknown browser controls fall back directly to its address bar`() {
        val cleanup = TabCleanup(target, 0)
        val ui = Ui(page).apply { rejected = setOf(TabAction.OPEN_TABS, TabAction.SUBMIT_BLANK) }
        cleanup.advance(ui, 0, true, wall)
        ui.observation = page.copy(editing = true)
        cleanup.advance(ui, 100, true, wall)
        ui.observation = page.copy(address = "about:blank", editing = true)
        cleanup.advance(ui, 200, true, wall)
        assertEquals(CleanupResult.FAILED, cleanup.result)
        assertFalse(TabAction.DISMISS_TABS in ui.actions)
    }

    @Test fun `changed browser host path window or preexisting edit cancels without actions`() {
        for (changed in listOf(
            page.copy(browser = "another.browser"), page.copy(address = "unrelated.test"),
            page.copy(address = "example.com/another-page"), page.copy(windowId = 99), page.copy(editing = true),
        )) {
            val cleanup = TabCleanup(target, 0)
            val ui = Ui(changed)
            cleanup.advance(ui, 0, true, wall)
            assertEquals(CleanupResult.CANCELLED, cleanup.result)
            assertTrue(ui.actions.isEmpty())
        }
    }

    @Test fun `reset snooze or deselecting the browser cancels any pending cleanup`() {
        val cleanup = TabCleanup(target, 0)
        val ui = Ui(page)
        cleanup.advance(ui, 0, true, wall)
        ui.observation = page.copy(canClose = true, tabsVisible = true)
        cleanup.advance(ui, 100, false, wall)
        assertEquals(CleanupResult.CANCELLED, cleanup.result)
        assertEquals(listOf(TabAction.OPEN_TABS), ui.actions)
    }

    @Test fun `block activity animation is allowed but leaving the browser cancels`() {
        val cleanup = TabCleanup(target, 0)
        val ui = Ui(page.copy(browser = wall))
        cleanup.advance(ui, 100, true, wall)
        assertNull(cleanup.result)
        ui.observation = page
        cleanup.advance(ui, 200, true, wall)
        ui.observation = page.copy(browser = "launcher")
        cleanup.advance(ui, 300, true, wall)
        assertEquals(CleanupResult.CANCELLED, cleanup.result)
        assertEquals(listOf(TabAction.OPEN_TABS), ui.actions)
    }

    @Test fun `hidden and unavailable windows time out without using the cached host`() {
        for (unavailable in listOf(page.copy(address = null), TabObservation(null))) {
            val cleanup = TabCleanup(target, 0)
            val ui = Ui(unavailable)
            cleanup.advance(ui, 0, true, wall)
            cleanup.advance(ui, 5_000, true, wall)
            assertEquals(CleanupResult.FAILED, cleanup.result)
            assertTrue(ui.actions.isEmpty())
        }
    }

    @Test fun `do not press back when the browser never opened a recognizable tab UI`() {
        val cleanup = TabCleanup(target, 0)
        val ui = Ui(page)
        cleanup.advance(ui, 0, true, wall)
        ui.observation = page.copy(address = null, windowId = 8)
        cleanup.advance(ui, 1_000, true, wall)
        assertEquals(CleanupResult.FAILED, cleanup.result)
        assertEquals(listOf(TabAction.OPEN_TABS), ui.actions)
    }

    @Test fun `switching tabs while returning from an overview prevents blanking`() {
        val cleanup = TabCleanup(target, 0)
        val ui = Ui(page)
        cleanup.advance(ui, 0, true, wall)
        ui.observation = page.copy(address = null, tabsVisible = true)
        cleanup.advance(ui, 1_000, true, wall)
        ui.observation = page.copy(address = "other.test")
        cleanup.advance(ui, 1_100, true, wall)
        assertEquals(CleanupResult.CANCELLED, cleanup.result)
        assertFalse(TabAction.SET_BLANK in ui.actions)
    }

    @Test fun `changing a visible path during address focus prevents blanking`() {
        val cleanup = TabCleanup(target.copy(address = "example.com/original"), 0)
        val ui = Ui(page.copy(address = "example.com/original")).apply { rejected = setOf(TabAction.OPEN_TABS) }
        cleanup.advance(ui, 0, true, wall)
        ui.observation = page.copy(address = "https://example.com/different", editing = true)
        cleanup.advance(ui, 100, true, wall)
        assertEquals(CleanupResult.CANCELLED, cleanup.result)
        assertFalse(TabAction.SET_BLANK in ui.actions)
    }
}
