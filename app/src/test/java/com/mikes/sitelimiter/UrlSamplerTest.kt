package com.mikes.sitelimiter

import org.junit.Assert.*
import org.junit.Test

class UrlSamplerTest {
    @Test fun `a missed final navigation event is recovered by the next timer sample`() {
        val sampler = UrlSampler()
        val tracker = HostTracker()
        var displayed: HostObservation = HostObservation.WebPage("old.example.com")
        var reads = 0
        fun sample(now: Long, contentEvent: Boolean = false) = sampler.sample(now, contentEvent) {
            reads++
            tracker.observe("browser", displayed, now, "day", true)
        }

        assertTrue(sample(1000))
        displayed = HostObservation.WebPage("new.example.com")
        assertFalse(sample(1500, contentEvent = true))
        assertEquals("old.example.com", tracker.host)
        // No more accessibility events arrive after the page becomes static.
        assertTrue(sample(6000))
        assertEquals("new.example.com", tracker.host)
        assertEquals(2, reads)
    }

    @Test fun `a recent content sample cannot suppress the next scheduled timer`() {
        val sampler = UrlSampler()
        val tracker = HostTracker()
        sampler.sample(5500, throttle = true) {
            tracker.observe("browser", HostObservation.WebPage("old.example.com"), 5500, "day", true)
        }
        // The timer fires inside the content throttle window, but must still observe navigation.
        assertTrue(sampler.sample(6000) {
            tracker.observe("browser", HostObservation.NoWebPage, 6000, "day", true)
        })
        assertNull(tracker.host)
    }

    @Test fun `content events resume at the throttle boundary and window changes bypass it`() {
        val sampler = UrlSampler()
        var reads = 0
        assertTrue(sampler.sample(1000, throttle = true) { reads++ })
        assertFalse(sampler.sample(1699, throttle = true) { reads++ })
        assertTrue(sampler.sample(1700, throttle = true) { reads++ })
        assertTrue(sampler.sample(1701) { reads++ })
        assertEquals(3, reads)
    }
}
