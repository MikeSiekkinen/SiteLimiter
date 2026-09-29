package com.mikes.sitelimiter

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class BudgetLogicTest {
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test fun `only real subdomains share a parent budget`() {
        assertTrue(BudgetLogic.matches("example.com", "example.com"))
        assertTrue(BudgetLogic.matches("news.example.com", "example.com"))
        assertFalse(BudgetLogic.matches("notexample.com", "example.com"))
        assertFalse(BudgetLogic.matches("example.com.evil.test", "example.com"))
    }

    @Test fun `a late reset keeps early morning in the previous budget day`() {
        val utc = ZoneId.of("UTC")
        assertEquals("2026-09-28", BudgetLogic.day(ms("2026-09-29T03:59:59Z"), 4, utc))
        assertEquals("2026-09-29", BudgetLogic.day(ms("2026-09-29T04:00:00Z"), 4, utc))
    }

    @Test fun `spring and autumn reset at the same local hour`() {
        val berlin = ZoneId.of("Europe/Berlin")
        assertEquals("2026-03-28", BudgetLogic.day(ms("2026-03-29T01:59:59Z"), 4, berlin))
        assertEquals("2026-03-29", BudgetLogic.day(ms("2026-03-29T02:00:00Z"), 4, berlin))
        assertEquals("2026-10-24", BudgetLogic.day(ms("2026-10-25T02:59:59Z"), 4, berlin))
        assertEquals("2026-10-25", BudgetLogic.day(ms("2026-10-25T03:00:00Z"), 4, berlin))
    }

    @Test fun `snooze expires exactly at its deadline and off today does not carry over`() {
        assertTrue(BudgetLogic.suppressed("2026-09-29", null, 1_000, 999))
        assertFalse(BudgetLogic.suppressed("2026-09-29", null, 1_000, 1_000))
        assertTrue(BudgetLogic.suppressed("2026-09-29", "2026-09-29", 0, 1_000))
        assertFalse(BudgetLogic.suppressed("2026-09-30", "2026-09-29", 0, 1_000))
    }

    @Test fun `frequent samples retain partial seconds instead of losing usage`() {
        val clock = UsageClock()
        assertNull(clock.observe("example.com", 1_000, "day", true))
        assertNull(clock.observe("example.com", 1_700, "day", true))
        assertEquals(UsageCharge("example.com", 1), clock.observe("example.com", 2_400, "day", true))
        assertEquals(UsageCharge("example.com", 1), clock.observe("example.com", 3_100, "day", true))
    }

    @Test fun `a page switch bills the preceding interval to the preceding host`() {
        val clock = UsageClock()
        clock.observe("one.test", 1_000, "day", true)
        assertEquals(UsageCharge("one.test", 3), clock.observe("two.test", 4_000, "day", true))
        assertEquals(UsageCharge("two.test", 1), clock.observe("two.test", 5_000, "day", true))
    }

    @Test fun `screen off and leaving watched browsers end the session`() {
        for (interactive in listOf(true, false)) {
            val clock = UsageClock()
            clock.observe("one.test", 1_000, "day", true)
            assertNull(clock.observe(if (interactive) null else "one.test", 6_000, "day", interactive))
            assertNull(clock.observe("one.test", 10_000, "day", true))
            assertEquals(UsageCharge("one.test", 1), clock.observe("one.test", 11_000, "day", true))
        }
    }

    @Test fun `sleep gaps and backwards monotonic samples never consume a budget`() {
        val clock = UsageClock()
        clock.observe("one.test", 10_000, "day", true)
        assertNull(clock.observe("one.test", 50_000, "day", true))
        assertNull(clock.observe("one.test", 40_000, "day", true))
        assertEquals(UsageCharge("one.test", 1), clock.observe("one.test", 41_000, "day", true))
    }

    @Test fun `an interval straddling reset is not charged to the new day`() {
        val clock = UsageClock()
        clock.observe("one.test", 1_000, "old", true)
        assertNull(clock.observe("one.test", 6_000, "new", true))
        assertEquals(UsageCharge("one.test", 1), clock.observe("one.test", 7_000, "new", true))
    }
}
