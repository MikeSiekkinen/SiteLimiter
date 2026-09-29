package com.mikes.sitelimiter

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import java.time.ZoneId

/**
 * Private, local persistence. Every instance reads and writes the same process-wide
 * [StateStore], so the activities and the accessibility service always see the same state
 * without re-reading storage.
 */
class Prefs(ctx: Context) {
    private val store = storeFor(ctx)
    private val zone get() = ZoneId.systemDefault()

    fun snapshot(now: Long = System.currentTimeMillis()): LimitState = store.get(now)

    private fun change(action: (LimitState, Long, ZoneId) -> LimitChange): ChangeResult {
        val now = System.currentTimeMillis()
        var result = ChangeResult.REJECTED
        store.update(now) { s -> action(s, now, zone).also { result = it.result }.state }
        return result
    }

    private fun update(action: (LimitState, Long, ZoneId) -> LimitState) {
        val now = System.currentTimeMillis()
        store.update(now) { s -> action(s, now, zone) }
    }

    fun rules(): List<Rule> = snapshot().rules
    fun matchingRules(host: String): List<Rule> = LimitPolicy.matchingRules(snapshot(), host)
    fun blockingRule(host: String): Rule? = LimitPolicy.blockingRule(snapshot(), host, System.currentTimeMillis())
    fun warningRule(host: String, warned: Set<String>): Rule? =
        LimitPolicy.warningRule(snapshot(), host, System.currentTimeMillis(), warned)

    fun upsertRule(domain: String, limitSeconds: Int, mode: RuleMode = RuleMode.NUDGE): ChangeResult {
        val normalized = normalizeDomain(domain) ?: return ChangeResult.REJECTED
        if (limitSeconds < 0) return ChangeResult.REJECTED
        return change { s, now, z -> LimitPolicy.changeRule(s, normalized, Rule(normalized, limitSeconds, mode), now, z) }
    }
    fun deleteRule(domain: String): ChangeResult = change { s, now, z -> LimitPolicy.changeRule(s, domain, null, now, z) }
    fun today(nowMs: Long = System.currentTimeMillis()): String = LimitPolicy.day(nowMs, resetHour(), zone)
    fun usedSeconds(domain: String): Int = snapshot().usage[domain] ?: 0
    fun addUsage(domain: String, seconds: Int) = update { s, now, z -> LimitPolicy.addUsage(s, domain, seconds, now, z) }
    fun addHostUsage(host: String, seconds: Int) = update { s, now, z -> LimitPolicy.addHostUsage(s, host, seconds, now, z) }
    /** Sample and credit the same budget period, even if a reset occurs during this call. */
    fun accrue(tracker: HostTracker, pkg: String?, observation: HostObservation, elapsed: Long, interactive: Boolean) {
        val now = System.currentTimeMillis()
        val state = snapshot(now)
        val charge = tracker.observe(pkg, observation, elapsed, state.endsAt.toString(), interactive) ?: return
        store.update(now, batch = true) { s -> LimitPolicy.addHostUsage(s, charge.host, charge.seconds, now, zone) }
    }
    /** Writes usage that [accrue] batched. Call when leaving a browser and on shutdown. */
    fun flush() = store.flush(System.currentTimeMillis())
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
    fun cancelPendingChanges() = update { s, _, _ -> s.copy(pendingRules = emptyMap(), pendingBrowsers = null, pendingResetHour = null) }

    companion object {
        private const val KEY_STATE = "state_v2"

        @Volatile private var shared: StateStore? = null

        private fun storeFor(ctx: Context): StateStore = shared ?: synchronized(this) {
            shared ?: run {
                val sp = ctx.applicationContext.getSharedPreferences("sitelimiter", Context.MODE_PRIVATE)
                StateStore(load = { now -> load(sp, now) }, save = { save(sp, it) }, zone = { ZoneId.systemDefault() })
            }.also { shared = it }
        }

        private fun load(sp: SharedPreferences, now: Long): LimitState {
            val raw = sp.getString(KEY_STATE, null) ?: return migrate(sp, now)
            return StateCodec.decode(raw, Browsers.DEFAULT_PACKAGES) ?: run {
                // Unreadable state falls back to the legacy keys, which are still present, or defaults.
                PrivacyLog.info(PrivacyLog.Event.STATE_UNREADABLE)
                migrate(sp, now)
            }
        }

        /**
         * The legacy keys are deliberately left in place, so a downgrade to a build from
         * before state_v2 still finds its rules. Remove them in a later version.
         */
        private fun save(sp: SharedPreferences, s: LimitState) {
            sp.edit().putString(KEY_STATE, StateCodec.encode(s)).apply()
        }

        /** Builds state from the pre-state_v2 keys: rules, today's usage, snoozes and browsers. */
        private fun migrate(sp: SharedPreferences, now: Long): LimitState {
            val hour = sp.getInt("reset_hour", 0).coerceIn(0, 23)
            val day = LimitPolicy.day(now, hour, ZoneId.systemDefault())
            val rules = parse(sp.getString("rules", null))?.let { StateCodec.rules(it) } ?: emptyList()
            return LimitState(
                rules = rules, resetHour = hour,
                browsers = parse(sp.getString("browsers", null))?.let { StateCodec.strings(it) } ?: Browsers.DEFAULT_PACKAGES,
                usage = rules.associate { it.domain to sp.getInt("usage:$day:${it.domain}", 0) },
                snoozes = rules.associate { it.domain to sp.getLong("snooze:${it.domain}", 0) },
                offToday = rules.filter { sp.getString("off:${it.domain}", null) == day }.map { it.domain }.toSet(),
            )
        }

        private fun parse(raw: String?): JSONArray? = raw?.let { runCatching { JSONArray(it) }.getOrNull() }

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
