package com.mikes.sitelimiter

import org.junit.Assert.*
import org.junit.Test

class BrowserTabControlsTest {
    private val chromium = TabControlsProfile.forBrowser("com.brave.browser")

    @Test fun `Chromium closes the exact menu action not close all incognito tabs`() {
        val controls = BrowserTabControls(listOf(
            TabControl(id = "close_all_incognito_tabs_menu_id", text = "Close all tabs", clickable = true),
            TabControl(id = "close_tab", text = "Close tab", clickable = true),
        ), chromium)
        assertEquals(1, controls.close())
    }

    @Test fun `localized Firefox label resolves the clickable menu row`() {
        val controls = BrowserTabControls(listOf(
            TabControl(clickable = true), TabControl(text = "Tab schließen", parent = 0),
            TabControl(text = "Alle Tabs schließen", clickable = true),
        ), TabControlsProfile.forBrowser("org.mozilla.firefox"), setOf("Tab schließen"))
        assertEquals(0, controls.close())
        assertTrue(controls.tabsVisible())
    }

    @Test fun `ambiguous close buttons and disabled controls are rejected`() {
        assertNull(BrowserTabControls(listOf(
            TabControl(id = "close_tab", clickable = true),
            TabControl(id = "close_tab", clickable = true),
        ), chromium).close())
        assertNull(BrowserTabControls(listOf(
            TabControl(id = "close_tab", clickable = true, enabled = false),
        ), chromium).close())
        assertNull(BrowserTabControls(listOf(
            TabControl(clickable = true),
            TabControl(id = "close_tab", parent = 0, enabled = false),
        ), chromium).close())
    }

    @Test fun `DuckDuckGo never long presses its new-tab shortcut`() {
        val profile = TabControlsProfile.forBrowser("com.duckduckgo.mobile.android")
        val controls = BrowserTabControls(listOf(TabControl(id = "tabsMenu", clickable = true)), profile)
        assertFalse(profile.longPress)
        assertEquals(0, controls.button())
    }

    @Test fun `overview close is restricted to the selected card`() {
        val profile = TabControlsProfile.forBrowser("com.duckduckgo.mobile.android")
        val nodes = listOf(
            TabControl(id = "tabsRecyclerView"),
            TabControl(className = "com.google.android.material.card.MaterialCardView", selected = false, parent = 0),
            TabControl(id = "close", clickable = true, parent = 1),
            TabControl(className = "com.google.android.material.card.MaterialCardView", selected = true, parent = 0),
            TabControl(id = "close", clickable = true, parent = 3),
        )
        assertEquals(4, BrowserTabControls(nodes, profile).close())
        assertNull(BrowserTabControls(nodes.map { it.copy(selected = false) }, profile).close())
        assertNull(BrowserTabControls(nodes.map { if (it.className.endsWith("CardView")) it.copy(selected = true) else it }, profile).close())
    }

    @Test fun `tab count can be read in different locales without guessing ambiguous numbers`() {
        assertEquals(3, BrowserTabControls(listOf(TabControl(id = "tab_switcher_button", description = "٣ tabs", longClickable = true)), chromium).count())
        assertNull(BrowserTabControls(listOf(TabControl(id = "tab_switcher_button", description = "3 tabs in 2 groups", longClickable = true)), chromium).count())
    }

    @Test fun `all listed browsers have a profile but arbitrary browsers never get guessed actions`() {
        for (pkg in Browsers.DEFAULT_PACKAGES) assertTrue(TabControlsProfile.forBrowser(pkg).buttons.isNotEmpty())
        val unknown = TabControlsProfile.forBrowser("custom.browser")
        assertNull(BrowserTabControls(listOf(TabControl(id = "tab_switcher_button", longClickable = true)), unknown).button())
    }
}
