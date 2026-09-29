package com.mikes.sitelimiter

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class StateStoreTest {
    private val now = Instant.parse("2026-09-29T12:00:00Z").toEpochMilli()
    private val zone = ZoneId.of("UTC")
    private val nudge = Rule("example.com", 600)
    private val hard = Rule("hard.com", 60, RuleMode.HARD)

    private var loads = 0
    private val saved = mutableListOf<LimitState>()
    private var disk = LimitState(rules = listOf(nudge, hard), browsers = setOf("browser.one"))

    private fun store() = StateStore(
        load = { loads++; disk },
        save = { saved += it; disk = it },
        zone = { zone },
    )

    @Test fun `steady-state reads load once and write nothing`() {
        val store = store()
        val first = store.get(now)
        saved.clear()
        repeat(1_000) { assertSame(first, store.get(now + it * 1_000L)) }
        assertEquals(1, loads)
        assertTrue(saved.isEmpty())
    }

    @Test fun `first read starts the budget period and persists it`() {
        val s = store().get(now)
        assertEquals("2026-09-29", s.day)
        assertEquals(Instant.parse("2026-09-30T00:00:00Z").toEpochMilli(), s.endsAt)
        assertEquals(s, saved.single())
    }

    @Test fun `a write through one caller is immediately visible to another`() {
        val store = store()
        val activity = { d: String -> store.update(now) { LimitPolicy.changeRule(it, d, null, now, zone).state } }
        val service = { store.get(now + 1) }
        store.get(now)
        activity(nudge.domain)
        assertEquals(listOf(hard), service().rules)
        assertEquals(listOf(hard), disk.rules)
        assertEquals(1, loads)
    }

    @Test fun `period rollover refreshes on read`() {
        val store = store()
        val start = store.get(now)
        store.update(now) { LimitPolicy.addUsage(it, nudge.domain, 30, now, zone) }
        saved.clear()
        val next = store.get(start.endsAt)
        assertEquals("2026-09-30", next.day)
        assertTrue(next.usage.isEmpty())
        assertEquals(next, saved.single())
    }

    @Test fun `batched usage waits for the save interval`() {
        val store = store()
        store.get(now)
        saved.clear()
        for (t in 1..5) store.update(now + t * 5_000L, batch = true) { LimitPolicy.addUsage(it, nudge.domain, 5, now, zone) }
        assertTrue(saved.isEmpty())
        store.update(now + 30_000L, batch = true) { LimitPolicy.addUsage(it, nudge.domain, 5, now, zone) }
        assertEquals(30, saved.single().usage[nudge.domain])
    }

    @Test fun `flush writes batched usage and is a no-op otherwise`() {
        val store = store()
        store.get(now)
        saved.clear()
        store.flush(now + 1)
        assertTrue(saved.isEmpty())
        store.update(now + 5_000L, batch = true) { LimitPolicy.addUsage(it, nudge.domain, 5, now, zone) }
        store.flush(now + 6_000L)
        assertEquals(5, saved.single().usage[nudge.domain])
        store.flush(now + 7_000L)
        assertEquals(1, saved.size)
    }

    @Test fun `usage that locks a hard limit is written immediately`() {
        val store = store()
        store.get(now)
        saved.clear()
        store.update(now + 1_000L, batch = true) { LimitPolicy.addUsage(it, hard.domain, 59, now, zone) }
        assertTrue(saved.isEmpty())
        store.update(now + 2_000L, batch = true) { LimitPolicy.addUsage(it, hard.domain, 1, now, zone) }
        assertEquals(setOf(hard.domain), saved.single().locked)
    }

    @Test fun `a non-batched change writes pending usage with it`() {
        val store = store()
        store.get(now)
        saved.clear()
        store.update(now + 1_000L, batch = true) { LimitPolicy.addUsage(it, nudge.domain, 5, now, zone) }
        store.update(now + 2_000L) { LimitPolicy.snooze(it, nudge.domain, 5, now, zone).state }
        val written = saved.single()
        assertEquals(5, written.usage[nudge.domain])
        assertTrue(nudge.domain in written.snoozes)
    }

    @Test fun `unchanged results are not written`() {
        val store = store()
        store.get(now)
        saved.clear()
        store.update(now + 1_000L) { LimitPolicy.addHostUsage(it, "unlimited.org", 5, now, zone) }
        assertTrue(saved.isEmpty())
    }
}
