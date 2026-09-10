package com.mikes.sitelimiter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the two pure functions everything else depends on. The awkward inputs here are not
 * invented: they were captured from Chrome and Brave on a real device.
 */
class HostParsingTest {

    // ---------- what the omnibox actually contains ----------

    @Test
    fun `brave shows the elided domain`() {
        assertEquals("reddit.com", Browsers.hostFromBarText("reddit.com"))
    }

    @Test
    fun `chrome shows a full url with a query string`() {
        // Verbatim from com.android.chrome:id/url_bar during a Reddit JS challenge redirect.
        val actual = "reddit.com/?solution=d5020b960398dee3&js_challenge=1&jsc_token=7afd7253"
        assertEquals("reddit.com", Browsers.hostFromBarText(actual))
    }

    @Test
    fun `www is stripped`() {
        assertEquals("reddit.com", Browsers.hostFromBarText("www.reddit.com"))
    }

    @Test
    fun `subdomains are preserved for the caller to match`() {
        assertEquals("old.reddit.com", Browsers.hostFromBarText("https://old.reddit.com/r/all"))
    }

    // ---------- the userinfo-vs-path ordering bug ----------

    @Test
    fun `an at sign in the query does not hijack the host`() {
        // Regression: stripping userinfo before cutting the path reported "evil.com" here.
        assertEquals("reddit.com", Browsers.hostFromBarText("reddit.com/x?email=a@evil.com"))
        assertEquals("reddit.com", Browsers.hostFromBarText("https://reddit.com/a?u=b@c.co/d"))
    }

    @Test
    fun `real userinfo is still stripped`() {
        assertEquals("reddit.com", Browsers.hostFromBarText("https://user:pass@reddit.com/x"))
    }

    @Test
    fun `port is stripped`() {
        assertEquals("example.com", Browsers.hostFromBarText("example.com:8080/path"))
    }

    // ---------- things that are not hosts ----------

    @Test
    fun `search terms are rejected`() {
        assertNull(Browsers.hostFromBarText("how do i stop procrastinating"))
        assertNull(Browsers.hostFromBarText("Search or type URL"))
    }

    @Test
    fun `empty and null are rejected`() {
        assertNull(Browsers.hostFromBarText(null))
        assertNull(Browsers.hostFromBarText(""))
        assertNull(Browsers.hostFromBarText("   "))
    }

    @Test
    fun `a bare word is not a host`() {
        assertNull(Browsers.hostFromBarText("reddit"))
    }

    // ---------- user input normalisation ----------

    @Test
    fun `normalize accepts what a user would paste`() {
        assertEquals("reddit.com", Prefs.normalizeDomain("https://www.Reddit.com/r/aww"))
        assertEquals("reddit.com", Prefs.normalizeDomain("  REDDIT.COM  "))
        assertEquals("reddit.com", Prefs.normalizeDomain("reddit.com"))
    }

    @Test
    fun `normalize rejects junk`() {
        assertNull(Prefs.normalizeDomain("not a domain"))
        assertNull(Prefs.normalizeDomain("reddit"))
        assertNull(Prefs.normalizeDomain(""))
    }

    @Test
    fun `normalize is consistent with bar parsing`() {
        // A rule added from a pasted URL must match the host the watcher will extract.
        val rule = Prefs.normalizeDomain("https://www.reddit.com/r/all?x=1")
        val seen = Browsers.hostFromBarText("www.reddit.com")
        assertEquals(rule, seen)
    }
}
