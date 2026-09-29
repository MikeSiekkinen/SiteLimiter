package com.mikes.sitelimiter

/** Native browser chrome only; the Android reader never descends into web content. */
internal data class TabControl(
    val id: String = "",
    val className: String = "",
    val text: String = "",
    val description: String = "",
    val parent: Int = -1,
    val selected: Boolean = false,
    val focused: Boolean = false,
    val editable: Boolean = false,
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val enabled: Boolean = true,
)

internal data class TabControlsProfile(
    val buttons: Set<String>,
    val longPress: Boolean,
    val closeIds: Set<String>,
    val closeStrings: Set<String>,
    val newTabStrings: Set<String>,
    val trays: Set<String> = emptySet(),
    val cardCloseIds: Set<String> = emptySet(),
    val editIds: Set<String> = emptySet(),
    val focusIds: Set<String> = emptySet(),
    val submitIds: Set<String> = emptySet(),
) {
    companion object {
        fun forBrowser(pkg: String): TabControlsProfile = when {
            pkg.startsWith("org.mozilla.") -> TabControlsProfile(
                setOf("tab_counter", "tabCounter", "counter_box", "mozac_tab_counter"), true,
                setOf("close_tab"), setOf("mozac_close_tab"), setOf("mozac_browser_menu_new_tab"),
                editIds = setOf("mozac_browser_toolbar_edit_url_view"),
                submitIds = setOf("mozac_browser_toolbar_edit_go"),
            )
            pkg == "com.duckduckgo.mobile.android" -> TabControlsProfile(
                // Long-press opens a NEW tab in DuckDuckGo; use the overview instead.
                setOf("tabsMenu", "tabSwitcherButton", "tabSwitcher", "tabsButton"), false,
                emptySet(), emptySet(), emptySet(),
                setOf("tabsRecycler", "tabSwitcherRecyclerView", "tabsRecyclerView", "tabSwitcherContainer"), setOf("close"),
                editIds = setOf("omnibarTextInput"),
                focusIds = setOf("omnibarTextInputClickCatcher"),
                submitIds = setOf("omnibarGoButton"),
            )
            pkg == "com.sec.android.app.sbrowser" -> TabControlsProfile(
                setOf("tab_switcher_button", "tab_button", "tabs_button"), false,
                emptySet(), emptySet(), emptySet(),
                setOf("tab_manager", "tab_manager_recycler_view", "tab_switcher"),
                setOf("tab_close_button", "close_tab_button"),
                editIds = setOf("location_bar_edit_text", "sbrowser_url_bar"),
                submitIds = setOf("location_bar_go_button"),
            )
            pkg.startsWith("com.opera.") -> TabControlsProfile(
                setOf("tab_switcher_button", "tab_counter", "tab_button"), false,
                emptySet(), emptySet(), emptySet(),
                setOf("tab_switcher", "tabs_list", "tab_grid"), setOf("close_tab", "tab_close_button"),
                editIds = setOf("url_field", "url_bar"),
            )
            pkg in Browsers.DEFAULT_PACKAGES -> TabControlsProfile(
                setOf("tab_switcher_button"), true,
                setOf("close_tab"), setOf("close_tab"), setOf("menu_new_tab"),
                editIds = setOf("url_bar"),
            )
            // Unknown browsers still get the address-bar fallback. Never guess what a
            // long-press does based merely on a browser's URL-bar resource ID.
            else -> TabControlsProfile(emptySet(), false, emptySet(), emptySet(), emptySet())
        }
    }
}

/** Selects only unambiguous controls. Labels come from the installed browser's resources. */
internal class BrowserTabControls(
    private val nodes: List<TabControl>,
    private val profile: TabControlsProfile,
    private val closeLabels: Set<String> = emptySet(),
    private val newTabLabels: Set<String> = emptySet(),
) {
    fun button(): Int? = unique(nodes.indices.filter { i ->
        val n = nodes[i]
        n.id in profile.buttons || (profile.longPress && n.className == "mozilla.components.ui.tabcounter.TabCounter")
    }.mapNotNull { actionable(it, profile.longPress) }.distinct())

    fun close(): Int? {
        if (profile.longPress) {
            return unique(nodes.indices.filter { i ->
                val n = nodes[i]
                n.id in profile.closeIds || n.text in closeLabels || n.description in closeLabels
            }.mapNotNull { actionable(it) }.distinct())
        }
        if (!overviewVisible()) return null
        // A generic "close" in the overview is not enough: require a selected card.
        // If the browser does not expose current-tab selection, blank the page instead.
        return unique(nodes.indices.filter { i ->
            nodes[i].id in profile.cardCloseIds && ancestors(i).any { ancestor ->
                val n = nodes[ancestor]
                n.selected && (n.className.endsWith("CardView") || n.id in CARD_IDS)
            }
        }.mapNotNull { actionable(it) }.distinct())
    }

    fun tabsVisible(): Boolean = overviewVisible() || close() != null || nodes.any {
        it.text in newTabLabels || it.description in newTabLabels
    }

    fun count(): Int? {
        val button = button() ?: return null
        val labels = listOf(nodes[button].text, nodes[button].description) + nodes.indices
            .filter { button in ancestors(it) }.map { nodes[it].text }
        val counts = labels.flatMap { label -> Regex("\\p{Nd}+").findAll(label).mapNotNull { match ->
            match.value.map { it.digitToIntOrNull() ?: return@mapNotNull null }
                .joinToString("").toIntOrNull()
        }.toList() }.distinct()
        return counts.singleOrNull()
    }

    private fun overviewVisible() = nodes.any { it.id in profile.trays }

    private fun actionable(index: Int, long: Boolean = false): Int? {
        if (!nodes[index].enabled) return null
        return (sequenceOf(index) + ancestors(index).take(2)).firstOrNull {
            nodes[it].enabled && if (long) nodes[it].longClickable else nodes[it].clickable
        }
    }

    private fun ancestors(index: Int): Sequence<Int> = sequence {
        var next = nodes[index].parent
        var remaining = nodes.size
        while (next in nodes.indices && remaining-- > 0) {
            yield(next)
            next = nodes[next].parent
        }
    }

    private fun unique(indices: List<Int>) = indices.singleOrNull()

    companion object {
        private val CARD_IDS = setOf("tab_item", "tab_view", "card_view", "tab_card", "cardContentsContainer")
    }
}
