package com.mikes.sitelimiter

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

data class Rule(val domain: String, val limitSeconds: Int)

/**
 * All state lives in one SharedPreferences file. No database, no network, no analytics.
 *
 * Keys:
 *   rules            JSON array of {domain, limit}
 *   browsers         JSON array of package names to watch
 *   reset_hour       int, local hour the daily budget rolls over (0 = midnight)
 *   usage:<day>:<domain>   seconds spent, day is the yyyy-MM-dd of the *budget* day
 *   snooze:<domain>        epoch millis until which blocking is suppressed
 *   off:<domain>           budget-day string on which blocking is off entirely
 */
class Prefs(ctx: Context) {

    private val sp = ctx.applicationContext.getSharedPreferences("sitelimiter", Context.MODE_PRIVATE)

    // ---------------- rules ----------------

    fun rules(): List<Rule> {
        val raw = sp.getString(KEY_RULES, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Rule(o.getString("domain"), o.getInt("limit"))
            }
        }.getOrDefault(emptyList())
    }

    fun saveRules(rules: List<Rule>) {
        val arr = JSONArray()
        rules.forEach { arr.put(JSONObject().put("domain", it.domain).put("limit", it.limitSeconds)) }
        sp.edit().putString(KEY_RULES, arr.toString()).apply()
    }

    /** Adds, or updates the limit if the domain is already present. */
    fun upsertRule(domain: String, limitSeconds: Int) {
        val normalized = normalizeDomain(domain) ?: return
        val next = rules().filter { it.domain != normalized } + Rule(normalized, limitSeconds)
        saveRules(next.sortedBy { it.domain })
    }

    fun deleteRule(domain: String) {
        saveRules(rules().filter { it.domain != domain })
        sp.edit()
            .remove(keySnooze(domain))
            .remove(keyOff(domain))
            .remove(keyUsage(today(), domain))
            .apply()
    }

    /** The rule governing a host, treating subdomains as part of the parent's budget. */
    fun matchRule(host: String): Rule? =
        rules().firstOrNull { host == it.domain || host.endsWith("." + it.domain) }

    // ---------------- usage ----------------

    /** The budget day, shifted by the configured reset hour. */
    fun today(nowMs: Long = System.currentTimeMillis()): String =
        Instant.ofEpochMilli(nowMs)
            .atZone(ZoneId.systemDefault())
            .minusHours(resetHour().toLong())
            .toLocalDate()
            .toString()

    fun usedSeconds(domain: String): Int = sp.getInt(keyUsage(today(), domain), 0)

    fun addUsage(domain: String, seconds: Int) {
        if (seconds <= 0) return
        val key = keyUsage(today(), domain)
        sp.edit().putInt(key, sp.getInt(key, 0) + seconds).apply()
    }

    fun resetUsage(domain: String) {
        sp.edit().remove(keyUsage(today(), domain)).apply()
    }

    /** Drops usage counters from previous budget days so the file cannot grow forever. */
    fun pruneOldUsage() {
        val today = today()
        val stale = sp.all.keys.filter { it.startsWith("usage:") && !it.startsWith("usage:$today:") }
        if (stale.isEmpty()) return
        sp.edit().apply { stale.forEach { remove(it) } }.apply()
    }

    // ---------------- snooze / off ----------------

    fun snoozeUntil(domain: String): Long = sp.getLong(keySnooze(domain), 0L)

    fun snooze(domain: String, minutes: Int) {
        sp.edit().putLong(keySnooze(domain), System.currentTimeMillis() + minutes * 60_000L).apply()
    }

    fun isOffToday(domain: String): Boolean = sp.getString(keyOff(domain), null) == today()

    fun turnOffToday(domain: String) {
        sp.edit().putString(keyOff(domain), today()).apply()
    }

    /** True when the limit should not be enforced right now, even though it has been exceeded. */
    fun blockingSuppressed(domain: String): Boolean =
        isOffToday(domain) || System.currentTimeMillis() < snoozeUntil(domain)

    // ---------------- browsers ----------------

    fun browsers(): Set<String> {
        val raw = sp.getString(KEY_BROWSERS, null) ?: return Browsers.DEFAULT_PACKAGES
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        }.getOrDefault(Browsers.DEFAULT_PACKAGES)
    }

    fun saveBrowsers(pkgs: Set<String>) {
        val arr = JSONArray()
        pkgs.sorted().forEach { arr.put(it) }
        sp.edit().putString(KEY_BROWSERS, arr.toString()).apply()
    }

    // ---------------- reset hour ----------------

    fun resetHour(): Int = sp.getInt(KEY_RESET_HOUR, 0)

    fun saveResetHour(hour: Int) {
        sp.edit().putInt(KEY_RESET_HOUR, hour.coerceIn(0, 23)).apply()
    }

    companion object {
        private const val KEY_RULES = "rules"
        private const val KEY_BROWSERS = "browsers"
        private const val KEY_RESET_HOUR = "reset_hour"

        private fun keyUsage(day: String, domain: String) = "usage:$day:$domain"
        private fun keySnooze(domain: String) = "snooze:$domain"
        private fun keyOff(domain: String) = "off:$domain"

        /** Turns whatever the user typed ("https://www.Reddit.com/r/x") into "reddit.com". */
        fun normalizeDomain(input: String): String? {
            var t = input.trim().lowercase()
            if (t.isEmpty()) return null
            if ("://" in t) t = t.substringAfter("://")
            t = t.substringBefore('/').substringBefore('?').substringBefore('#')
            t = t.substringAfter('@')
            t = t.substringBefore(':')
            t = t.removePrefix("www.")
            return if (Browsers.HOST_RE.matches(t)) t else null
        }
    }
}
