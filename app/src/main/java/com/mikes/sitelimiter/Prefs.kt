package com.mikes.sitelimiter

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId

/** Private, local persistence. Policy reads always refresh from disk-backed preferences so
 * the activity and accessibility service cannot act on stale rule or lock snapshots. */
class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("sitelimiter", Context.MODE_PRIVATE)
    private val zone get() = ZoneId.systemDefault()

    fun snapshot(): LimitState {
        val raw = sp.getString("state_v2", null)
        val before = if (raw == null) migrate() else decode(JSONObject(raw))
        val after = LimitPolicy.refresh(before, System.currentTimeMillis(), zone)
        if (raw == null || before != after) store(after)
        return after
    }

    private fun change(action: (LimitState, Long, ZoneId) -> LimitChange): ChangeResult {
        val result = action(snapshot(), System.currentTimeMillis(), zone)
        store(result.state)
        return result.result
    }

    fun rules(): List<Rule> = snapshot().rules
    fun matchingRules(host: String): List<Rule> = rules().filter { it.matches(host) }
    fun matchRule(host: String): Rule? = matchingRules(host).firstOrNull()
    fun blockingRule(host: String): Rule? = LimitPolicy.blockingRule(snapshot(), host, System.currentTimeMillis())

    fun upsertRule(domain: String, limitSeconds: Int, mode: RuleMode = RuleMode.NUDGE): ChangeResult {
        val normalized = normalizeDomain(domain) ?: return ChangeResult.REJECTED
        if (limitSeconds < 0) return ChangeResult.REJECTED
        return change { s, now, z -> LimitPolicy.changeRule(s, normalized, Rule(normalized, limitSeconds, mode), now, z) }
    }
    fun deleteRule(domain: String): ChangeResult = change { s, now, z -> LimitPolicy.changeRule(s, domain, null, now, z) }
    fun today(nowMs: Long = System.currentTimeMillis()): String = LimitPolicy.day(nowMs, resetHour(), zone)
    fun usedSeconds(domain: String): Int = snapshot().usage[domain] ?: 0
    fun addUsage(domain: String, seconds: Int) { store(LimitPolicy.addUsage(snapshot(), domain, seconds, System.currentTimeMillis(), zone)) }
    fun addHostUsage(host: String, seconds: Int) { store(LimitPolicy.addHostUsage(snapshot(), host, seconds, System.currentTimeMillis(), zone)) }
    fun resetUsage(domain: String): ChangeResult = change { s, now, z -> LimitPolicy.resetUsage(s, domain, now, z) }
    fun pruneOldUsage() { snapshot() }
    fun snoozeUntil(domain: String): Long = snapshot().snoozes[domain] ?: 0
    fun snooze(domain: String, minutes: Int): ChangeResult = change { s, now, z -> LimitPolicy.snooze(s, domain, minutes, now, z) }
    fun isOffToday(domain: String): Boolean = domain in snapshot().offToday
    fun turnOffToday(domain: String): ChangeResult = change { s, now, z -> LimitPolicy.turnOffToday(s, domain, now, z) }
    fun blockingSuppressed(domain: String): Boolean {
        val s = snapshot()
        val rule = s.rules.firstOrNull { it.domain == domain } ?: return false
        return LimitPolicy.suppressed(s, rule, System.currentTimeMillis())
    }
    fun browsers(): Set<String> = snapshot().browsers
    fun saveBrowsers(pkgs: Set<String>): ChangeResult = change { s, now, z -> LimitPolicy.changeBrowsers(s, pkgs, now, z) }
    fun resetHour(): Int = snapshot().resetHour
    fun saveResetHour(hour: Int): ChangeResult = change { s, now, z -> LimitPolicy.changeResetHour(s, hour, now, z) }
    fun cancelPendingChanges() { store(snapshot().copy(pendingRules = emptyMap(), pendingBrowsers = null, pendingResetHour = null)) }

    /** One-time migration keeps existing rules, today's usage, snoozes and browser selection. */
    private fun migrate(): LimitState {
        val hour = sp.getInt("reset_hour", 0).coerceIn(0, 23)
        val day = LimitPolicy.day(System.currentTimeMillis(), hour, zone)
        val rules = objects(JSONArray(sp.getString("rules", "[]"))).map { readRule(it) }
        return LimitState(
            rules = rules, resetHour = hour,
            browsers = sp.getString("browsers", null)?.let { strings(JSONArray(it)) } ?: Browsers.DEFAULT_PACKAGES,
            usage = rules.associate { it.domain to sp.getInt("usage:$day:${it.domain}", 0) },
            snoozes = rules.associate { it.domain to sp.getLong("snooze:${it.domain}", 0) },
            offToday = rules.filter { sp.getString("off:${it.domain}", null) == day }.map { it.domain }.toSet(),
        )
    }

    private fun store(s: LimitState) {
        val o = JSONObject().put("rules", JSONArray().apply { s.rules.forEach { put(writeRule(it)) } })
            .put("browsers", JSONArray(s.browsers.sorted())).put("resetHour", s.resetHour)
            .put("day", s.day).put("endsAt", s.endsAt).put("usage", JSONObject(s.usage))
            .put("snoozes", JSONObject(s.snoozes)).put("offToday", JSONArray(s.offToday.sorted()))
            .put("locked", JSONArray(s.locked.sorted()))
            .put("pendingRules", JSONArray().apply { s.pendingRules.forEach { (domain, rule) ->
                put(rule?.let { writeRule(it) } ?: JSONObject().put("domain", domain).put("delete", true))
            } })
        s.pendingBrowsers?.let { o.put("pendingBrowsers", JSONArray(it.sorted())) }
        s.pendingResetHour?.let { o.put("pendingResetHour", it) }
        // Remove migrated legacy keys as well as stale counters in a single atomic edit.
        sp.edit().clear().putString("state_v2", o.toString()).apply()
    }

    private fun decode(o: JSONObject): LimitState = LimitState(
        rules = objects(o.getJSONArray("rules")).map { readRule(it) },
        browsers = strings(o.getJSONArray("browsers")), resetHour = o.getInt("resetHour"),
        day = o.getString("day"), endsAt = o.getLong("endsAt"),
        usage = o.getJSONObject("usage").let { map -> map.keys().asSequence().associateWith { map.getInt(it) } },
        snoozes = o.getJSONObject("snoozes").let { map -> map.keys().asSequence().associateWith { map.getLong(it) } },
        offToday = strings(o.getJSONArray("offToday")), locked = strings(o.getJSONArray("locked")),
        pendingRules = objects(o.getJSONArray("pendingRules")).associate { it.getString("domain") to if (it.optBoolean("delete")) null else readRule(it) },
        pendingBrowsers = o.optJSONArray("pendingBrowsers")?.let { strings(it) },
        pendingResetHour = if (o.has("pendingResetHour")) o.getInt("pendingResetHour") else null,
    )

    private fun readRule(o: JSONObject) = Rule(o.getString("domain"), o.getInt("limit"),
        if (o.optBoolean("hard", false)) RuleMode.HARD else RuleMode.NUDGE)
    private fun writeRule(r: Rule) = JSONObject().put("domain", r.domain).put("limit", r.limitSeconds).put("hard", r.mode == RuleMode.HARD)
    private fun objects(a: JSONArray): List<JSONObject> = (0 until a.length()).map { a.getJSONObject(it) }
    private fun strings(a: JSONArray): Set<String> = (0 until a.length()).map { a.getString(it) }.toSet()

    companion object {
        fun normalizeDomain(input: String): String? {
            var t = input.trim().lowercase()
            if (t.isEmpty()) return null
            if ("://" in t) t = t.substringAfter("://")
            t = t.substringBefore('/').substringBefore('?').substringBefore('#')
            t = t.substringAfter('@').substringBefore(':').removePrefix("www.")
            return if (Browsers.HOST_RE.matches(t)) t else null
        }
    }
}
