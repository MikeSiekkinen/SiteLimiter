package com.mikes.sitelimiter

import org.junit.Assert.*
import org.junit.Test

class StateCodecTest {
    private val defaults = setOf("default.browser")

    private val full = LimitState(
        rules = listOf(Rule("example.com", 600), Rule("hard.com", 60, RuleMode.HARD)),
        browsers = setOf("browser.one", "browser.two"),
        resetHour = 4, day = "2026-09-29", endsAt = 1_790_744_400_000L,
        usage = mapOf("example.com" to 120, "hard.com" to 60),
        snoozes = mapOf("example.com" to 1_790_700_000_000L),
        offToday = setOf("example.com"), locked = setOf("hard.com"),
        pendingRules = mapOf("hard.com" to null, "example.com" to Rule("example.com", 900, RuleMode.HARD)),
        pendingBrowsers = setOf("browser.one"), pendingResetHour = 5,
    )

    @Test fun `round trip preserves every field`() {
        assertEquals(full, StateCodec.decode(StateCodec.encode(full), defaults))
    }

    @Test fun `round trip without pending changes`() {
        val s = full.copy(pendingRules = emptyMap(), pendingBrowsers = null, pendingResetHour = null)
        assertEquals(s, StateCodec.decode(StateCodec.encode(s), defaults))
    }

    @Test fun `legacy mirror keys round trip and keep the fields older builds read`() {
        val rules = StateCodec.encodeRules(full.rules)
        assertEquals(full.rules, StateCodec.rules(org.json.JSONArray(rules)))
        val first = org.json.JSONArray(rules).getJSONObject(0)
        assertEquals("example.com", first.getString("domain"))
        assertEquals(600, first.getInt("limit"))
        assertEquals(full.browsers, StateCodec.strings(org.json.JSONArray(StateCodec.encodeStrings(full.browsers))))
    }

    @Test fun `text that is not a JSON object is unreadable`() {
        assertNull(StateCodec.decode("", defaults))
        assertNull(StateCodec.decode("{\"rules\":", defaults))
        assertNull(StateCodec.decode("[]", defaults))
    }

    @Test fun `missing fields fall back to defaults and keep the rules`() {
        val s = StateCodec.decode("""{"rules":[{"domain":"example.com","limit":600}]}""", defaults)!!
        assertEquals(listOf(Rule("example.com", 600)), s.rules)
        assertEquals(defaults, s.browsers)
        assertEquals(0, s.resetHour)
        assertEquals(0L, s.endsAt)
        assertTrue(s.usage.isEmpty() && s.locked.isEmpty() && s.pendingRules.isEmpty())
        assertNull(s.pendingBrowsers)
        assertNull(s.pendingResetHour)
    }

    @Test fun `malformed values are skipped instead of discarding the state`() {
        val raw = """{
            "rules":[{"domain":"good.com","limit":60,"hard":true},{"domain":"","limit":5},{"limit":5},
                     {"domain":"negative.com","limit":-1},"not an object"],
            "browsers":"not an array", "resetHour":99, "endsAt":"soon",
            "usage":{"good.com":30,"bad.com":"lots"}, "locked":["good.com"],
            "pendingRules":[{"domain":"good.com","delete":true},{"nodomain":1}], "pendingResetHour":42
        }"""
        val s = StateCodec.decode(raw, defaults)!!
        assertEquals(listOf(Rule("good.com", 60, RuleMode.HARD)), s.rules)
        assertEquals(defaults, s.browsers)
        assertEquals(0, s.resetHour)
        assertEquals(0L, s.endsAt)
        assertEquals(mapOf("good.com" to 30), s.usage)
        assertEquals(setOf("good.com"), s.locked)
        assertEquals(mapOf("good.com" to null), s.pendingRules)
        assertNull(s.pendingResetHour)
    }
}
