package com.mikes.sitelimiter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HostTrackerTest {
    private fun HostTracker.observe(pkg: String?, observation: HostObservation, now: Long) =
        observe(pkg, observation, now, "day", true)

    private val chrome = "com.android.chrome"
    private val brave = "com.brave.browser"
    private val page = HostObservation.WebPage("video.example.com")

    @Test
    fun `blank and internal pages bank the old host then stop accruing`() {
        for (text in listOf("", "chrome://newtab", "about:blank", "chrome://example.com")) {
            val tracker = HostTracker(15000)
            tracker.observe(chrome, page, 1000)
            assertEquals(
                UsageCharge(page.host, 5),
                tracker.observe(chrome, Browsers.observeUrlBar(text, null, false), 6000),
            )
            assertNull(tracker.host)
            assertNull(tracker.observe(chrome, HostObservation.ToolbarUnavailable, 11000))
        }
    }

    @Test
    fun `focused URL editing pauses usage without tracking the typed host`() {
        val tracker = HostTracker(15000)
        tracker.observe(chrome, page, 1000)
        assertEquals(
            UsageCharge(page.host, 2),
            tracker.observe(chrome, Browsers.observeUrlBar("other.com", null, true), 3000),
        )
        assertNull(tracker.host)
        assertNull(tracker.observe(chrome, HostObservation.Editing, 10000))
        assertNull(tracker.observe(chrome, HostObservation.WebPage("other.com"), 11000))
        assertEquals(UsageCharge("other.com", 5), tracker.observe(chrome, HostObservation.ToolbarUnavailable, 16000))
    }

    @Test
    fun `hidden toolbar preserves the host only in its owning browser`() {
        val tracker = HostTracker(15000)
        tracker.observe(chrome, page, 1000)
        assertEquals(UsageCharge(page.host, 5), tracker.observe(chrome, HostObservation.ToolbarUnavailable, 6000))
        assertEquals(page.host, tracker.host)
        assertNull(tracker.observe(brave, HostObservation.ToolbarUnavailable, 11000))
        assertEquals(brave, tracker.browserPackage)
        assertNull(tracker.host)
        assertNull(tracker.observe(brave, HostObservation.ToolbarUnavailable, 16000))
    }

    @Test
    fun `leaving a browser or turning the screen off cannot charge the inactive interval`() {
        val tracker = HostTracker(15000)
        tracker.observe(chrome, page, 1000)
        assertNull(tracker.observe(null, HostObservation.ToolbarUnavailable, 4000))
        assertNull(tracker.browserPackage)
        assertNull(tracker.host)
        assertNull(tracker.observe(chrome, HostObservation.ToolbarUnavailable, 9000))
        assertNull(tracker.observe(chrome, page, 10000))
        assertEquals(UsageCharge(page.host, 5), tracker.observe(chrome, page, 15000))
    }


    @Test
    fun `repeated short samples carry fractional seconds without drift`() {
        val tracker = HostTracker(15000)
        tracker.observe(chrome, page, 0)
        val total = (1..100).sumOf { tracker.observe(chrome, page, it * 700L)?.seconds ?: 0 }
        assertEquals(70, total)
    }

    @Test
    fun `time from one host is not carried into another host`() {
        val tracker = HostTracker(15000)
        tracker.observe(chrome, page, 0)
        assertNull(tracker.observe(chrome, HostObservation.WebPage("other.com"), 900))
        assertNull(tracker.observe(chrome, HostObservation.ToolbarUnavailable, 1100))
        assertEquals(UsageCharge("other.com", 1), tracker.observe(chrome, HostObservation.ToolbarUnavailable, 1900))
    }

    @Test
    fun `sleep gaps are discarded and normal accrual resumes`() {
        val tracker = HostTracker(15000)
        tracker.observe(chrome, page, 1000)
        assertNull(tracker.observe(chrome, page, 3601000))
        assertEquals(UsageCharge(page.host, 5), tracker.observe(chrome, page, 3606000))
    }


    @Test
    fun `an unavailable active window and screen off both discard unknown intervals`() {
        for (interactive in listOf(true, false)) {
            val tracker = HostTracker()
            tracker.observe(chrome, page, 1000)
            assertNull(tracker.observe(chrome, HostObservation.WindowUnavailable, 6000, "day", interactive))
            assertNull(tracker.host)
            assertNull(tracker.browserPackage)
            assertNull(tracker.observe(chrome, HostObservation.ToolbarUnavailable, 11000))
        }
    }

    @Test
    fun `budget period changes never charge an old interval to the new budget`() {
        val tracker = HostTracker()
        tracker.observe(chrome, page, 1000, "old", true)
        assertNull(tracker.observe(chrome, page, 6000, "new", true))
        assertEquals(UsageCharge(page.host, 5), tracker.observe(chrome, page, 11000, "new", true))
    }
}
