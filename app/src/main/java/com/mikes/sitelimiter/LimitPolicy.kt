package com.mikes.sitelimiter

import java.time.Instant
import java.time.ZoneId

enum class RuleMode { NUDGE, HARD }

data class Rule(val domain: String, val limitSeconds: Int, val mode: RuleMode = RuleMode.NUDGE) {
    init { require(limitSeconds >= 0) }
    fun matches(host: String): Boolean = BudgetLogic.matches(host, domain)
}

/** A snapshot of the current budget period. Contains no browsing history. */
data class LimitState(
    val rules: List<Rule> = emptyList(),
    val browsers: Set<String> = emptySet(),
    val resetHour: Int = 0,
    val day: String = "",
    val endsAt: Long = 0,
    val usage: Map<String, Int> = emptyMap(),
    val snoozes: Map<String, Long> = emptyMap(),
    val offToday: Set<String> = emptySet(),
    val locked: Set<String> = emptySet(),
    // A null replacement means a scheduled deletion.
    val pendingRules: Map<String, Rule?> = emptyMap(),
    val pendingBrowsers: Set<String>? = null,
    val pendingResetHour: Int? = null,
) {
    val hasPending: Boolean get() = pendingRules.isNotEmpty() || pendingBrowsers != null || pendingResetHour != null
}

enum class ChangeResult { UPDATED, QUEUED, REJECTED }
data class LimitChange(val state: LimitState, val result: ChangeResult)

/** All enforcement decisions live here, independently of Android, storage and UI callbacks. */
object LimitPolicy {
    private const val WARN_SECONDS = 5 * 60

    /** Every ancestor budget applies; the most specific match is presented first. */
    fun matchingRules(state: LimitState, host: String): List<Rule> = state.rules
        .filter { it.matches(host) }
        .sortedWith(compareByDescending<Rule> { it.domain.length }.thenBy { it.domain })

    fun day(now: Long, hour: Int, zone: ZoneId): String = BudgetLogic.day(now, hour, zone)

    fun nextReset(now: Long, hour: Int, zone: ZoneId): Long {
        val local = Instant.ofEpochMilli(now).atZone(zone)
        val today = local.toLocalDate().atTime(hour, 0).atZone(zone)
        return (if (today.toInstant().toEpochMilli() > now) today
                else local.toLocalDate().plusDays(1).atTime(hour, 0).atZone(zone)).toInstant().toEpochMilli()
    }

    /** Longest a budget period can legitimately run: a day that gains an hour at a DST change. */
    const val MAX_PERIOD_MS = 25 * 3_600_000L

    /** True while [state]'s period is current, so it can be used without [refresh]. */
    fun periodCurrent(state: LimitState, now: Long): Boolean =
        now < state.endsAt && state.endsAt - now <= MAX_PERIOD_MS

    fun refresh(state: LimitState, now: Long, zone: ZoneId): LimitState {
        var s = state
        // No period yet, or one ending further ahead than any period can, which only happens when
        // the clock moved back. Re-anchor to the next real reset but keep usage, locks and
        // scheduled changes: setting the clock back must neither freeze the period nor lift a limit.
        if (s.endsAt == 0L || s.endsAt - now > MAX_PERIOD_MS) {
            s = s.copy(day = day(now, s.resetHour, zone), endsAt = nextReset(now, s.resetHour, zone))
        } else if (now >= s.endsAt) {
            val rules = s.rules.associateBy { it.domain }.toMutableMap()
            s.pendingRules.forEach { (domain, rule) -> if (rule == null) rules.remove(domain) else rules[domain] = rule }
            val hour = s.pendingResetHour ?: s.resetHour
            s = s.copy(
                rules = rules.values.sortedBy { it.domain },
                browsers = s.pendingBrowsers ?: s.browsers,
                resetHour = hour, day = day(now, hour, zone), endsAt = nextReset(now, hour, zone),
                usage = emptyMap(), offToday = emptySet(), locked = emptySet(),
                pendingRules = emptyMap(), pendingBrowsers = null, pendingResetHour = null,
            )
        }
        val domains = s.rules.map { it.domain }.toSet()
        return s.copy(
            usage = s.usage.filterKeys { it in domains },
            snoozes = s.snoozes.filter { (d, expiry) -> d in domains && expiry > now && s.rules.any { it.domain == d && it.mode == RuleMode.NUDGE } },
            offToday = s.offToday.filterTo(mutableSetOf()) { d -> s.rules.any { it.domain == d && it.mode == RuleMode.NUDGE } },
            locked = s.locked + s.rules.filter { it.mode == RuleMode.HARD && (s.usage[it.domain] ?: 0) >= it.limitSeconds }.map { it.domain },
        )
    }

    fun changeRule(state: LimitState, domain: String, replacement: Rule?, now: Long, zone: ZoneId): LimitChange {
        require(replacement == null || replacement.domain == domain)
        val s = refresh(state, now, zone)
        val old = s.rules.firstOrNull { it.domain == domain }
        val tightening = replacement != null && replacement.mode == RuleMode.HARD && old != null && replacement.limitSeconds <= old.limitSeconds
        if (domain in s.locked && !tightening) {
            return LimitChange(s.copy(pendingRules = s.pendingRules + (domain to replacement)), ChangeResult.QUEUED)
        }
        val rules = s.rules.filterNot { it.domain == domain } + listOfNotNull(replacement)
        val next = s.copy(
            rules = rules.sortedBy { it.domain }, pendingRules = s.pendingRules - domain,
            usage = if (replacement == null) s.usage - domain else s.usage,
            snoozes = if (replacement == null || replacement.mode == RuleMode.HARD) s.snoozes - domain else s.snoozes,
            offToday = if (replacement == null || replacement.mode == RuleMode.HARD) s.offToday - domain else s.offToday,
        )
        return LimitChange(refresh(next, now, zone), ChangeResult.UPDATED)
    }

    fun resetUsage(state: LimitState, domain: String, now: Long, zone: ZoneId): LimitChange {
        val s = refresh(state, now, zone)
        return if (domain in s.locked) LimitChange(s, ChangeResult.REJECTED)
        else LimitChange(s.copy(usage = s.usage - domain), ChangeResult.UPDATED)
    }

    fun changeBrowsers(state: LimitState, requested: Set<String>, now: Long, zone: ZoneId): LimitChange {
        val s = refresh(state, now, zone)
        return if (s.locked.isNotEmpty() && !requested.containsAll(s.browsers)) {
            LimitChange(s.copy(browsers = s.browsers + requested, pendingBrowsers = requested), ChangeResult.QUEUED)
        } else LimitChange(s.copy(browsers = requested, pendingBrowsers = null), ChangeResult.UPDATED)
    }

    fun changeResetHour(state: LimitState, hour: Int, now: Long, zone: ZoneId): LimitChange {
        require(hour in 0..23)
        val s = refresh(state, now, zone)
        return if (s.locked.isNotEmpty()) {
            if (hour != s.resetHour) LimitChange(s.copy(pendingResetHour = hour), ChangeResult.QUEUED)
            else LimitChange(s.copy(pendingResetHour = null), ChangeResult.UPDATED)
        } else LimitChange(s.copy(resetHour = hour, pendingResetHour = null,
            day = day(now, hour, zone), endsAt = nextReset(now, hour, zone)), ChangeResult.UPDATED)
    }

    fun addUsage(state: LimitState, domain: String, seconds: Int, now: Long, zone: ZoneId): LimitState {
        val s = refresh(state, now, zone)
        if (seconds <= 0 || s.rules.none { it.domain == domain }) return s
        val used = ((s.usage[domain] ?: 0).toLong() + seconds).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return refresh(s.copy(usage = s.usage + (domain to used)), now, zone)
    }

    fun addHostUsage(state: LimitState, host: String, seconds: Int, now: Long, zone: ZoneId): LimitState {
        val s = refresh(state, now, zone)
        return matchingRules(s, host).fold(s) { result, rule ->
            addUsage(result, rule.domain, seconds, now, zone)
        }
    }

    fun snooze(state: LimitState, domain: String, minutes: Int, now: Long, zone: ZoneId): LimitChange {
        val s = refresh(state, now, zone)
        if (minutes !in setOf(5, 15) || s.rules.none { it.domain == domain && it.mode == RuleMode.NUDGE }) return LimitChange(s, ChangeResult.REJECTED)
        return LimitChange(s.copy(snoozes = s.snoozes + (domain to now + minutes * 60_000L)), ChangeResult.UPDATED)
    }

    fun turnOffToday(state: LimitState, domain: String, now: Long, zone: ZoneId): LimitChange {
        val s = refresh(state, now, zone)
        if (s.rules.none { it.domain == domain && it.mode == RuleMode.NUDGE }) return LimitChange(s, ChangeResult.REJECTED)
        return LimitChange(s.copy(offToday = s.offToday + domain), ChangeResult.UPDATED)
    }

    fun suppressed(state: LimitState, rule: Rule, now: Long): Boolean = rule.mode == RuleMode.NUDGE &&
        BudgetLogic.suppressed(state.day, if (rule.domain in state.offToday) state.day else null, state.snoozes[rule.domain] ?: 0, now)

    fun blockingRule(state: LimitState, host: String, now: Long): Rule? = matchingRules(state, host)
        .filter { ((state.usage[it.domain] ?: 0) >= it.limitSeconds || it.domain in state.locked) && !suppressed(state, it, now) }
        .sortedWith(compareByDescending<Rule> { it.mode == RuleMode.HARD }.thenByDescending { it.domain.length }.thenBy { it.domain })
        .firstOrNull()

    /** Show one warning at a time, without marking warnings that the user never saw. */
    fun warningRule(state: LimitState, host: String, now: Long, warned: Set<String>): Rule? {
        if (blockingRule(state, host, now) != null) return null
        return matchingRules(state, host).firstOrNull {
            it.domain !in warned && !suppressed(state, it, now) &&
                it.limitSeconds - (state.usage[it.domain] ?: 0) in 1..WARN_SECONDS
        }
    }
}
