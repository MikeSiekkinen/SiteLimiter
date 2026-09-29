package com.mikes.sitelimiter

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class HardLimitTest {
    private val now = Instant.parse("2026-09-29T12:00:00Z").toEpochMilli()
    private val zone = ZoneId.of("UTC")
    private val hard = Rule("example.com", 60, RuleMode.HARD)
    private fun state(used: Int = 60, rule: Rule = hard) = LimitPolicy.refresh(
        LimitState(rules = listOf(rule), browsers = setOf("browser.one"), usage = mapOf(rule.domain to used)), now, zone)

    @Test fun `existing rules default to nudges`() {
        val old = Rule("example.com", 60)
        val s = state(rule = old)
        assertTrue(s.locked.isEmpty())
        val snoozed = LimitPolicy.snooze(s, old.domain, 5, now, zone)
        assertEquals(ChangeResult.UPDATED, snoozed.result)
        assertNull(LimitPolicy.blockingRule(snoozed.state, "example.com", now))
        assertEquals(old, LimitPolicy.blockingRule(snoozed.state, "example.com", now + 300_000))
    }

    @Test fun `hard limit cannot be snoozed or turned off even with old nudge exceptions`() {
        val s = state().copy(snoozes = mapOf(hard.domain to now + 900_000), offToday = setOf(hard.domain))
        assertEquals(hard, LimitPolicy.blockingRule(s, "example.com", now))
        assertEquals(ChangeResult.REJECTED, LimitPolicy.snooze(s, hard.domain, 15, now, zone).result)
        assertEquals(ChangeResult.REJECTED, LimitPolicy.turnOffToday(s, hard.domain, now, zone).result)
    }

    @Test fun `settings remain editable until the budget is exhausted`() {
        val s = state(59)
        assertEquals(ChangeResult.UPDATED, LimitPolicy.changeRule(s, hard.domain, hard.copy(mode = RuleMode.NUDGE), now, zone).result)
        assertEquals(ChangeResult.UPDATED, LimitPolicy.resetUsage(s, hard.domain, now, zone).result)
        assertEquals(ChangeResult.UPDATED, LimitPolicy.changeBrowsers(s, emptySet(), now, zone).result)
        assertEquals(ChangeResult.UPDATED, LimitPolicy.changeResetHour(s, 4, now, zone).result)
        assertNull(LimitPolicy.blockingRule(s, "example.com", now))
    }

    @Test fun `crossing the budget locks immediately and persists in the stored snapshot`() {
        val exhausted = LimitPolicy.addUsage(state(59), hard.domain, 1, now, zone)
        val restored = LimitPolicy.refresh(exhausted.copy(), now + 1_000, zone)
        assertTrue(hard.domain in restored.locked)
        assertEquals(ChangeResult.REJECTED, LimitPolicy.resetUsage(restored, hard.domain, now + 1_000, zone).result)
        assertEquals(hard, LimitPolicy.blockingRule(restored, "example.com", now + 1_000))
    }

    @Test fun `a zero budget is locked without a first browsing session`() {
        val s = state(0, hard.copy(limitSeconds = 0))
        assertTrue(hard.domain in s.locked)
        assertEquals(ChangeResult.QUEUED, LimitPolicy.changeRule(s, hard.domain, null, now, zone).result)
    }

    @Test fun `raising deleting or softening an exhausted rule waits for the original reset`() {
        for (replacement in listOf(hard.copy(limitSeconds = 120), hard.copy(mode = RuleMode.NUDGE), null)) {
            val s = state()
            val pending = LimitPolicy.changeRule(s, hard.domain, replacement, now, zone)
            assertEquals(ChangeResult.QUEUED, pending.result)
            assertEquals(hard, LimitPolicy.blockingRule(pending.state, "example.com", now))
            val beforeReset = LimitPolicy.refresh(pending.state, s.endsAt - 1, zone)
            assertEquals(listOf(hard), beforeReset.rules)
            val afterReset = LimitPolicy.refresh(pending.state, s.endsAt, zone)
            assertEquals(listOfNotNull(replacement), afterReset.rules)
            assertTrue(afterReset.usage.isEmpty())
            assertTrue(afterReset.pendingRules.isEmpty())
            assertTrue(afterReset.locked.isEmpty())
        }
    }

    @Test fun `tightening an exhausted rule is immediate and cancels an earlier pending edit`() {
        val pending = LimitPolicy.changeRule(state(), hard.domain, hard.copy(limitSeconds = 120), now, zone).state
        val change = LimitPolicy.changeRule(pending, hard.domain, hard.copy(limitSeconds = 30), now, zone)
        assertEquals(ChangeResult.UPDATED, change.result)
        assertEquals(30, change.state.rules.single().limitSeconds)
        assertTrue(change.state.pendingRules.isEmpty())
        assertTrue(hard.domain in change.state.locked)
    }

    @Test fun `removing browsers waits while additions are watched immediately`() {
        val s = state()
        val change = LimitPolicy.changeBrowsers(s, setOf("browser.two"), now, zone)
        assertEquals(ChangeResult.QUEUED, change.result)
        assertEquals(setOf("browser.one", "browser.two"), change.state.browsers)
        assertEquals(setOf("browser.two"), LimitPolicy.refresh(change.state, s.endsAt, zone).browsers)
        val cancel = LimitPolicy.changeBrowsers(change.state, setOf("browser.one", "browser.two"), now, zone)
        assertNull(cancel.state.pendingBrowsers)
    }

    @Test fun `changing reset hour cannot shorten an exhausted period`() {
        val s = state()
        val pending = LimitPolicy.changeResetHour(s, 13, now, zone).state
        assertEquals(s.endsAt, pending.endsAt)
        assertEquals(0, pending.resetHour)
        val after = LimitPolicy.refresh(pending, s.endsAt, zone)
        assertEquals(13, after.resetHour)
        assertEquals(Instant.parse("2026-09-30T13:00:00Z").toEpochMilli(), after.endsAt)
    }

    @Test fun `saving the current reset hour cancels a pending hour without moving the locked boundary`() {
        val s = state()
        val pending = LimitPolicy.changeResetHour(s, 13, now, zone).state
        val cancelled = LimitPolicy.changeResetHour(pending, 0, now, ZoneId.of("Asia/Tokyo")).state
        assertNull(cancelled.pendingResetHour)
        assertEquals(s.endsAt, cancelled.endsAt)
        assertEquals(s.locked, cancelled.locked)
    }

    @Test fun `a soft subdomain cannot bypass an exhausted hard parent`() {
        val soft = Rule("news.example.com", 1)
        val s = state().copy(rules = listOf(soft, hard), usage = mapOf(soft.domain to 100, hard.domain to 60), offToday = setOf(soft.domain))
        assertEquals(hard, LimitPolicy.blockingRule(s, "news.example.com", now))
        assertNull(LimitPolicy.blockingRule(s, "notexample.com", now))
    }

    @Test fun `most specific exhausted hard rule is shown regardless of rule order`() {
        val child = Rule("news.example.com", 20, RuleMode.HARD)
        val s = state().copy(rules = listOf(hard, child), usage = mapOf(hard.domain to 60, child.domain to 20))
        assertEquals(child, LimitPolicy.blockingRule(s, child.domain, now))
    }

    @Test fun `pending changes are applied only once even after several days offline`() {
        val pending = LimitPolicy.changeRule(state(), hard.domain, null, now, zone).state
        val resumed = LimitPolicy.refresh(pending, now + 3 * 86_400_000L, zone)
        assertTrue(resumed.rules.isEmpty())
        assertFalse(resumed.hasPending)
        assertEquals(resumed, LimitPolicy.refresh(resumed, now + 3 * 86_400_000L + 1, zone))
    }

    @Test fun `reset uses local calendar boundaries across daylight saving changes`() {
        val berlin = ZoneId.of("Europe/Berlin")
        val spring = Instant.parse("2026-03-28T03:00:00Z").toEpochMilli() // 04:00 local
        val autumn = Instant.parse("2026-10-24T02:00:00Z").toEpochMilli() // 04:00 local
        assertEquals(23 * 3_600_000L, LimitPolicy.nextReset(spring, 4, berlin) - spring)
        assertEquals(25 * 3_600_000L, LimitPolicy.nextReset(autumn, 4, berlin) - autumn)
    }

    @Test fun `usage arithmetic cannot wrap and unlock an exhausted budget`() {
        val s = state(Int.MAX_VALUE - 1)
        assertEquals(Int.MAX_VALUE, LimitPolicy.addUsage(s, hard.domain, 10, now, zone).usage[hard.domain])
        assertEquals(ChangeResult.REJECTED, LimitPolicy.resetUsage(s, hard.domain, now, zone).result)
    }
    @Test fun `overlapping rules each receive the same foreground interval`() {
        val child = Rule("news.example.com", 30)
        val s = state(0).copy(rules = listOf(hard, child))
        val counted = LimitPolicy.addHostUsage(s, child.domain, 30, now, zone)
        assertEquals(30, counted.usage[hard.domain])
        assertEquals(30, counted.usage[child.domain])
        assertNull(counted.usage["unrelated.test"])
    }

    @Test fun `accrual resets when a deferred reset hour keeps the same calendar day label`() {
        val exhausted = state()
        val pending = LimitPolicy.changeResetHour(exhausted, 13, now, zone).state
        val reset = LimitPolicy.refresh(pending, pending.endsAt, zone)
        assertEquals(pending.day, reset.day)
        val clock = UsageClock()
        clock.observe(hard.domain, 1_000, pending.endsAt.toString(), true)
        assertNull(clock.observe(hard.domain, 6_000, reset.endsAt.toString(), true))
        val charge = clock.observe(hard.domain, 8_000, reset.endsAt.toString(), true)!!
        val counted = LimitPolicy.addHostUsage(reset, charge.host, charge.seconds, pending.endsAt + 2_000, zone)
        assertEquals(2, counted.usage[hard.domain])
        assertTrue(counted.locked.isEmpty())
    }

}
