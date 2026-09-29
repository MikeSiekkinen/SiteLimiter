package com.mikes.sitelimiter

import java.time.Instant
import java.time.ZoneId

/** Calendar days, not fixed 24-hour durations: a budget reset survives DST changes. */
object BudgetLogic {
    fun day(nowMs: Long, resetHour: Int, zone: ZoneId): String {
        val local = Instant.ofEpochMilli(nowMs).atZone(zone)
        val reset = local.toLocalDate().atTime(resetHour, 0).atZone(zone).toInstant()
        return (if (local.toInstant() < reset) local.toLocalDate().minusDays(1) else local.toLocalDate()).toString()
    }

    fun matches(host: String, domain: String): Boolean = host == domain || host.endsWith(".$domain")

    fun suppressed(day: String, offDay: String?, snoozeUntil: Long, now: Long): Boolean =
        day == offDay || now < snoozeUntil
}

data class UsageCharge(val host: String, val seconds: Int)

/** Accounts only observed foreground sessions. Long sleep gaps and unobserved transitions
 * are discarded. Sub-second remainders are retained within a continuous host session. */
class UsageClock(private val maxGapMs: Long = 15_000L) {
    private var owner: String? = null
    private var day: String? = null
    private var last: Long? = null

    fun observe(host: String?, now: Long, budgetDay: String, interactive: Boolean): UsageCharge? {
        if (!interactive || host == null) {
            owner = null
            last = null
            day = budgetDay
            return null
        }
        val previous = owner
        val start = last
        if (previous == null || start == null || day != budgetDay) {
            owner = host; day = budgetDay; last = now
            return null
        }
        val elapsed = now - start
        if (elapsed < 0 || elapsed > maxGapMs) {
            owner = host; last = now
            return null
        }
        val seconds = (elapsed / 1_000).toInt()
        // A remainder belongs to the old host; do not bill it to a newly opened page.
        last = if (previous != host) now else start + seconds * 1_000L
        owner = host
        return if (seconds > 0) UsageCharge(previous, seconds) else null
    }
}
