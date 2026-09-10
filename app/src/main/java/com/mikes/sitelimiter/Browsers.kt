package com.mikes.sitelimiter

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * Which packages count as browsers, and where each one keeps its URL bar in the
 * accessibility tree.
 *
 * Every Chromium fork (Chrome, Brave, Edge, Vivaldi, Kiwi, Opera) exposes the omnibox as
 * "<package>:id/url_bar", which is why the default below covers most of the field for free.
 */
object Browsers {

    val DEFAULT_PACKAGES: Set<String> = setOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
        "com.brave.browser",
        "com.brave.browser_beta",
        "com.brave.browser_nightly",
        "com.microsoft.emmx",
        "com.opera.browser",
        "com.opera.gx",
        "com.vivaldi.browser",
        "com.kiwibrowser.browser",
        "org.mozilla.firefox",
        "org.mozilla.fenix",
        "com.duckduckgo.mobile.android",
        "com.sec.android.app.sbrowser",
    )

    /** View ids to try, in order, for a given browser package. */
    fun urlBarIds(pkg: String): List<String> = when {
        pkg.startsWith("org.mozilla") -> listOf(
            "$pkg:id/mozac_browser_toolbar_url_view",
            "$pkg:id/url_bar_title",
        )
        pkg == "com.sec.android.app.sbrowser" -> listOf(
            "$pkg:id/location_bar_edit_text",
            "$pkg:id/sbrowser_url_bar",
        )
        pkg == "com.duckduckgo.mobile.android" -> listOf(
            "$pkg:id/omnibarTextInput",
        )
        // Chromium family, and a reasonable guess for anything unknown.
        else -> listOf("$pkg:id/url_bar")
    }

    /** Suffixes used by the fallback tree scan for browsers not in the table above. */
    val URL_BAR_ID_SUFFIXES = listOf(
        ":id/url_bar",
        ":id/mozac_browser_toolbar_url_view",
        ":id/location_bar_edit_text",
        ":id/omnibarTextInput",
        ":id/url_field",
    )

    val HOST_RE = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")

    /**
     * Pulls a bare host out of whatever the URL bar is displaying. Chromium usually shows an
     * elided "reddit.com"; when focused it shows the full URL. Anything that is not a plausible
     * hostname (search terms, "Search or type URL") returns null.
     */
    fun hostFromBarText(raw: CharSequence?): String? {
        var t = raw?.toString()?.trim()?.lowercase() ?: return null
        if (t.isEmpty() || ' ' in t) return null
        if ("://" in t) t = t.substringAfter("://")
        // Cut the path/query FIRST. Chrome shows full URLs in the omnibox, so a query such as
        // "?email=a@b.com" would otherwise make the userinfo strip below swallow the real host
        // and report b.com.
        t = t.substringBefore('/').substringBefore('?').substringBefore('#')
        t = t.substringAfter('@')   // userinfo
        t = t.substringBefore(':')  // port
        t = t.removePrefix("www.")
        return if (HOST_RE.matches(t)) t else null
    }

    data class BrowserApp(val pkg: String, val label: String)

    /** Every app on the device that can open an https:// link. */
    fun installed(ctx: Context): List<BrowserApp> {
        val pm = ctx.packageManager
        // CATEGORY_BROWSABLE is what makes this "browsers" rather than "anything that
        // registered an https link handler".
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        val flags = PackageManager.MATCH_ALL
        return pm.queryIntentActivities(intent, flags)
            .mapNotNull { ri ->
                val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == ctx.packageName) return@mapNotNull null
                BrowserApp(pkg, ri.loadLabel(pm).toString())
            }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }
}
