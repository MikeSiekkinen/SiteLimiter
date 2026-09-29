package com.mikes.sitelimiter

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * JSON form of [LimitState]. Decoding is lenient: a missing or malformed field falls back to
 * its default and a bad rule is skipped, so one damaged value cannot take every rule with it.
 */
object StateCodec {

    fun encode(s: LimitState): String {
        val o = JSONObject().put("rules", rulesArray(s.rules))
            .put("browsers", JSONArray(s.browsers.sorted())).put("resetHour", s.resetHour)
            .put("day", s.day).put("endsAt", s.endsAt).put("usage", JSONObject(s.usage))
            .put("snoozes", JSONObject(s.snoozes)).put("offToday", JSONArray(s.offToday.sorted()))
            .put("locked", JSONArray(s.locked.sorted()))
            .put("pendingRules", JSONArray().apply { s.pendingRules.forEach { (domain, rule) ->
                put(rule?.let { writeRule(it) } ?: JSONObject().put("domain", domain).put("delete", true))
            } })
        s.pendingBrowsers?.let { o.put("pendingBrowsers", JSONArray(it.sorted())) }
        s.pendingResetHour?.let { o.put("pendingResetHour", it) }
        return o.toString()
    }

    /** Null only when [raw] is not a JSON object at all. */
    fun decode(raw: String, defaultBrowsers: Set<String>): LimitState? {
        val o = try {
            JSONObject(raw)
        } catch (e: JSONException) {
            return null
        }
        val rules = rules(o.optJSONArray("rules"))
        return LimitState(
            rules = rules,
            browsers = o.optJSONArray("browsers")?.let { strings(it) } ?: defaultBrowsers,
            resetHour = o.optInt("resetHour", 0).takeIf { it in 0..23 } ?: 0,
            day = o.optString("day", ""),
            // 0 makes the next read compute a new day and end time instead of trusting a bad
            // value. Usage, locks and off-for-today are kept, so a damaged value cannot lift a limit.
            endsAt = o.optLong("endsAt", 0L),
            usage = ints(o.optJSONObject("usage")),
            snoozes = longs(o.optJSONObject("snoozes")),
            offToday = o.optJSONArray("offToday")?.let { strings(it) } ?: emptySet(),
            locked = o.optJSONArray("locked")?.let { strings(it) } ?: emptySet(),
            pendingRules = pendingRules(o.optJSONArray("pendingRules")),
            pendingBrowsers = o.optJSONArray("pendingBrowsers")?.let { strings(it) },
            pendingResetHour = if (o.has("pendingResetHour")) o.optInt("pendingResetHour", -1).takeIf { it in 0..23 } else null,
        )
    }

    /** Also used to read the legacy `rules` key during migration. */
    fun rules(a: JSONArray?): List<Rule> = objects(a).mapNotNull { readRule(it) }

    /** The legacy `rules` key. Builds from before hard limits read it and ignore `hard`. */
    fun encodeRules(rules: List<Rule>): String = rulesArray(rules).toString()

    /** The legacy `browsers` key. */
    fun encodeStrings(values: Set<String>): String = JSONArray(values.sorted()).toString()

    private fun rulesArray(rules: List<Rule>) = JSONArray().apply { rules.forEach { put(writeRule(it)) } }

    private fun pendingRules(a: JSONArray?): Map<String, Rule?> = buildMap {
        for (o in objects(a)) {
            val domain = o.optString("domain").takeIf { it.isNotEmpty() } ?: continue
            if (o.optBoolean("delete")) put(domain, null) else readRule(o)?.let { put(domain, it) }
        }
    }

    private fun readRule(o: JSONObject): Rule? {
        val domain = o.optString("domain").takeIf { it.isNotEmpty() } ?: return null
        val limit = o.optInt("limit", -1).takeIf { it >= 0 } ?: return null
        return Rule(domain, limit, if (o.optBoolean("hard", false)) RuleMode.HARD else RuleMode.NUDGE)
    }

    private fun writeRule(r: Rule) =
        JSONObject().put("domain", r.domain).put("limit", r.limitSeconds).put("hard", r.mode == RuleMode.HARD)

    private fun objects(a: JSONArray?): List<JSONObject> =
        if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }

    fun strings(a: JSONArray): Set<String> =
        (0 until a.length()).mapNotNull { a.optString(it, "").takeIf { s -> s.isNotEmpty() } }.toSet()

    private fun ints(o: JSONObject?): Map<String, Int> = buildMap {
        o?.keys()?.forEach { k -> o.optInt(k, -1).takeIf { it >= 0 }?.let { put(k, it) } }
    }

    private fun longs(o: JSONObject?): Map<String, Long> = buildMap {
        o?.keys()?.forEach { k -> o.optLong(k, -1L).takeIf { it >= 0 }?.let { put(k, it) } }
    }
}
