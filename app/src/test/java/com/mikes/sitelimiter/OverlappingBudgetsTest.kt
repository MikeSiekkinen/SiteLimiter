package com.mikes.sitelimiter

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class OverlappingBudgetsTest {
    private val now = Instant.parse("2026-09-29T12:00:00Z").toEpochMilli()
    private val zone = ZoneId.of("UTC")
    private val parent = Rule("example.com", 1800)
    private val child = Rule("video.example.com", 600)
    private val nested = Rule("live.video.example.com", 60)
    private fun state(rules: List<Rule> = listOf(parent, child, nested)) =
        LimitPolicy.refresh(LimitState(rules = rules), now, zone)

    @Test fun `all ancestors match by specificity independently of saved order`() {
        for (rules in listOf(listOf(parent, nested, child), listOf(child, nested, parent))) {
            val s = state(rules)
            assertEquals(listOf(nested, child, parent), LimitPolicy.matchingRules(s, nested.domain))
            assertEquals(listOf(child, parent), LimitPolicy.matchingRules(s, child.domain))
            assertEquals(listOf(parent), LimitPolicy.matchingRules(s, parent.domain))
            assertTrue(LimitPolicy.matchingRules(s, "notexample.com").isEmpty())
            assertTrue(LimitPolicy.matchingRules(s, "example.com.evil.com").isEmpty())
        }
    }

    @Test fun `zero minute child blocks while the parent still has time`() {
        val zero = child.copy(limitSeconds = 0)
        assertEquals(zero, LimitPolicy.blockingRule(state(listOf(parent, zero)), child.domain, now))
    }

    @Test fun `snooze and off for today suppress only the selected exhausted rule`() {
        var s = state().copy(usage = mapOf(parent.domain to 1800, child.domain to 600, nested.domain to 60))
        assertEquals(nested, LimitPolicy.blockingRule(s, nested.domain, now))
        s = LimitPolicy.snooze(s, nested.domain, 5, now, zone).state
        assertEquals(child, LimitPolicy.blockingRule(s, nested.domain, now))
        s = LimitPolicy.turnOffToday(s, child.domain, now, zone).state
        assertEquals(parent, LimitPolicy.blockingRule(s, nested.domain, now))
        s = LimitPolicy.snooze(s, parent.domain, 15, now, zone).state
        assertNull(LimitPolicy.blockingRule(s, nested.domain, now))
        assertEquals(nested, LimitPolicy.blockingRule(s, nested.domain, now + 300_000))
    }

    @Test fun `suppressed ancestors still accrue the complete foreground interval`() {
        val unrelated = Rule("other.com", 60)
        var s = state(listOf(parent, child, nested, unrelated))
            .copy(usage = mapOf(parent.domain to 10, child.domain to 20, unrelated.domain to 30))
        s = LimitPolicy.snooze(s, parent.domain, 5, now, zone).state
        s = LimitPolicy.turnOffToday(s, child.domain, now, zone).state
        s = LimitPolicy.addHostUsage(s, nested.domain, 5, now, zone)
        assertEquals(mapOf(parent.domain to 15, child.domain to 25, nested.domain to 5, unrelated.domain to 30), s.usage)
    }

    @Test fun `an exhausted parent takes priority over a child warning`() {
        val s = state(listOf(parent, child)).copy(usage = mapOf(parent.domain to 1800, child.domain to 590))
        assertEquals(parent, LimitPolicy.blockingRule(s, child.domain, now))
        assertNull(LimitPolicy.warningRule(s, child.domain, now, emptySet()))
    }

    @Test fun `only the displayed warning is marked and each ancestor can warn once`() {
        val s = state(listOf(parent, child)).copy(usage = mapOf(parent.domain to 1600, child.domain to 400))
        val warned = mutableSetOf<String>()
        for (rule in listOf(child, parent)) {
            assertEquals(rule, LimitPolicy.warningRule(s, child.domain, now, warned))
            assertFalse(rule.domain in warned)
            warned.add(rule.domain)
        }
        assertNull(LimitPolicy.warningRule(s, child.domain, now, warned))
        warned.clear()
        assertEquals(child, LimitPolicy.warningRule(s, child.domain, now, warned))
    }

    @Test fun `suppressed budgets and budgets with ample time do not warn`() {
        val s = state(listOf(parent, child)).copy(usage = mapOf(child.domain to 400), offToday = setOf(child.domain))
        assertNull(LimitPolicy.warningRule(s, child.domain, now, emptySet()))
    }
}
