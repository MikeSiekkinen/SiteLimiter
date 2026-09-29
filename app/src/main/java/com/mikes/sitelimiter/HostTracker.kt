package com.mikes.sitelimiter

sealed interface HostObservation {
    data class WebPage(val host: String) : HostObservation
    data object NoWebPage : HostObservation
    data object Editing : HostObservation
    data object ToolbarUnavailable : HostObservation
    data object WindowUnavailable : HostObservation
}

/** Browser ownership and URL state, using the existing budget-period-aware usage clock. */
class HostTracker(maxAccrualMs: Long = 15_000L) {
    private val clock = UsageClock(maxAccrualMs)
    var browserPackage: String? = null
        private set
    var host: String? = null
        private set

    fun observe(
        pkg: String?, observation: HostObservation, now: Long, budgetDay: String, interactive: Boolean,
    ): UsageCharge? {
        if (!interactive || pkg == null || observation == HostObservation.WindowUnavailable) {
            clock.observe(null, now, budgetDay, false)
            browserPackage = null
            host = null
            return null
        }
        if (pkg != browserPackage) {
            // Switching apps leaves an unobserved interval; never charge it to either browser.
            clock.observe(null, now, budgetDay, true)
            host = null
        }
        val nextHost = when (observation) {
            is HostObservation.WebPage -> observation.host
            HostObservation.ToolbarUnavailable -> host
            else -> null
        }
        // A confirmed transition inside the browser banks the old page before clearing it.
        val charge = clock.observe(nextHost ?: host, now, budgetDay, true)
        if (nextHost == null) clock.observe(null, now, budgetDay, true)
        browserPackage = pkg
        host = nextHost
        return charge
    }
}
