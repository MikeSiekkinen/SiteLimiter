package com.mikes.sitelimiter

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * Watches the URL bar of the browsers the user selected, accrues time against the daily
 * budget for any matching domain, and raises [BlockActivity] when the budget runs out.
 *
 * Tracks the host visible in the foreground browser; hidden URL bars remain a detection
 * limitation for both nudge and hard rules.
 */
class UrlWatcherService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    // Lazy rather than lateinit: nothing here should depend on onServiceConnected having run
    // before the first event or tick arrives.
    private val prefs by lazy { Prefs(this) }
    private val power by lazy { getSystemService(Context.POWER_SERVICE) as PowerManager }

    private var browsers: Set<String> = emptySet()
    private var filteredBrowsers: Set<String>? = null

    private val tracker = HostTracker(MAX_ACCRUAL_MS)
    private var lastExtractMs = 0L
    private var lastFallbackScanMs = 0L
    private var lastFallbackScanPkg: String? = null
    private var lastBlockMs = 0L

    /** Domains already warned about today, so the nudge fires once. */
    private val warned = mutableSetOf<String>()
    private var currentDay = ""

    private val ticker = object : Runnable {
        override fun run() {
            try {
                tick(verifyForeground = true)
            } catch (t: Throwable) {
                PrivacyLog.warning(PrivacyLog.Event.TICK_FAILED, t)
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        refreshBrowserFilter()
        rollDayIfNeeded()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        PrivacyLog.info(PrivacyLog.Event.CONNECTED)
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        try {
            stopAccruing()
        } catch (t: Throwable) {
            PrivacyLog.warning(PrivacyLog.Event.FLUSH_FAILED, t)
        }
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // An uncaught throw here gets the whole service torn down by the system, and the
        // failure mode is silent: tracking just stops and nothing says so.
        try {
            handleEvent(event)
        } catch (t: Throwable) {
            PrivacyLog.warning(PrivacyLog.Event.EVENT_FAILED, t)
        }
    }

    private fun handleEvent(event: AccessibilityEvent) {
        refreshBrowserFilter()
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in browsers || activePackage() != pkg) return

        if (pkg != tracker.browserPackage) {
            // The transition time is unknown; discard the unobserved interval.
            stopAccruing()
        }

        // Content-change events fire constantly while a page renders; sample them.
        val now = SystemClock.elapsedRealtime()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            now - lastExtractMs < EXTRACT_THROTTLE_MS
        ) return
        lastExtractMs = now

        recordHost(pkg, extractHost(pkg))
        // The event already told us the foreground package, so skip the extra window lookup.
        tick(verifyForeground = false)
    }

    private fun refreshBrowserFilter() {
        browsers = prefs.browsers()
        if (filteredBrowsers == browsers) return
        val info = serviceInfo ?: return
        // Android interprets null/empty package filters as "all apps". Use our own package
        // when watching nothing, and reject it in handleEvent without reading its content.
        info.packageNames = browsers.ifEmpty { setOf(packageName) }.sorted().toTypedArray()
        serviceInfo = info
        filteredBrowsers = browsers
    }

    // ---------------- time accounting ----------------

    private fun recordHost(pkg: String?, observation: HostObservation) {
        prefs.accrue(tracker, pkg, observation, SystemClock.elapsedRealtime(), power.isInteractive)
    }

    private fun stopAccruing() = recordHost(null, HostObservation.WindowUnavailable)

    /**
     * @param verifyForeground re-read the active window to confirm what is really in front.
     * Costs a binder round trip, so only the timer does it; events carry their own package.
     */
    private fun tick(verifyForeground: Boolean) {
        refreshBrowserFilter()
        rollDayIfNeeded()

        // No accessibility events arrive while the screen is off, so check it explicitly.
        if (!power.isInteractive) {
            stopAccruing()
            return
        }

        // The active window is the authoritative answer to "what is in front". Event ordering
        // alone is not: opening the notification shade makes systemui the foreground package,
        // and dismissing it does not reliably produce a new state-change event for the browser,
        // so a static page would stop being counted until something else happened to fire.
        val pkg = if (verifyForeground) activePackage() else tracker.browserPackage
        if (pkg == null || pkg !in browsers) {
            stopAccruing()
            return
        }

        // Poll even after filtering non-browser events: a static page may not emit a new
        // event after the user switches back from another app or the notification shade.
        recordHost(pkg, extractHost(pkg))
        val host = tracker.host ?: return

        val blocked = prefs.blockingRule(host)
        if (blocked != null) {
            block(blocked, prefs.usedSeconds(blocked.domain), host)
        } else {
            prefs.warningRule(host, warned)?.let { warnOnce(it) }
        }
    }

    /** Clears per-day state and prunes stale counters when the budget day rolls over. */
    private fun rollDayIfNeeded() {
        val day = prefs.snapshot().endsAt.toString()
        if (day == currentDay) return
        currentDay = day
        warned.clear()
        prefs.pruneOldUsage()
    }

    private fun warnOnce(rule: Rule) {
        if (!warned.add(rule.domain)) return
        val left = ((rule.limitSeconds - prefs.usedSeconds(rule.domain)) / 60).coerceAtLeast(1)
        Toast.makeText(this, "$left min left on ${rule.domain}", Toast.LENGTH_LONG).show()
    }

    private fun block(rule: Rule, usedSeconds: Int, host: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBlockMs < BLOCK_DEBOUNCE_MS) return
        lastBlockMs = now

        if (Settings.canDrawOverlays(this)) {
            val intent = Intent(this, BlockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(BlockActivity.EXTRA_DOMAIN, rule.domain)
                .putExtra(BlockActivity.EXTRA_HOST, host)
                .putExtra(BlockActivity.EXTRA_USED, usedSeconds)
                .putExtra(BlockActivity.EXTRA_LIMIT, rule.limitSeconds)
            try {
                startActivity(intent)
                return
            } catch (t: Throwable) {
                // Background activity starts can still be refused. Fall through rather than
                // letting the limit silently do nothing.
                PrivacyLog.warning(PrivacyLog.Event.BLOCK_FAILED, t)
            }
        }

        Toast.makeText(this, "${rule.domain}: daily limit reached", Toast.LENGTH_LONG).show()
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    // ---------------- URL extraction ----------------

    private fun activePackage(): String? {
        val root = activeRoot() ?: return null
        return try {
            root.packageName?.toString()
        } finally {
            recycleQuietly(root)
        }
    }

    private fun activeRoot(): AccessibilityNodeInfo? = try {
        rootInActiveWindow
    } catch (t: Throwable) {
        PrivacyLog.warning(PrivacyLog.Event.ROOT_FAILED, t)
        null
    }

    private fun extractHost(pkg: String): HostObservation {
        val root = activeRoot() ?: return HostObservation.WindowUnavailable
        try {
            if (root.packageName?.toString() != pkg) return HostObservation.WindowUnavailable

            for (id in Browsers.urlBarIds(pkg)) {
                val nodes = root.findAccessibilityNodeInfosByViewId(id) ?: continue
                try {
                    for (node in nodes) {
                        if (!node.isVisibleToUser) continue
                        return Browsers.observeUrlBar(node.text, node.contentDescription, node.isFocused)
                    }
                } finally {
                    nodes.forEach { recycleQuietly(it) }
                }
            }

            // Unknown browser, or the toolbar is hidden because the page is scrolled. The scan
            // is the expensive path, so rate-limit it hard.
            val now = SystemClock.elapsedRealtime()
            if (pkg == lastFallbackScanPkg && now - lastFallbackScanMs < FALLBACK_SCAN_INTERVAL_MS) {
                return HostObservation.ToolbarUnavailable
            }
            lastFallbackScanMs = now
            lastFallbackScanPkg = pkg
            return scanForUrlBar(root)
        } finally {
            recycleQuietly(root)
        }
    }

    private fun scanForUrlBar(root: AccessibilityNodeInfo): HostObservation {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        // Nodes this scan obtained and therefore owns. The root belongs to the caller and must
        // not be recycled here, or the caller's own recycle becomes a double free.
        val owned = ArrayList<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0

        try {
            while (queue.isNotEmpty() && visited < MAX_SCAN_NODES) {
                val node = queue.removeFirst()
                visited++

                val viewId = node.viewIdResourceName
                if (viewId != null &&
                    Browsers.URL_BAR_ID_SUFFIXES.any { viewId.endsWith(it) } &&
                    node.isVisibleToUser
                ) {
                    return Browsers.observeUrlBar(node.text, node.contentDescription, node.isFocused)
                }

                for (i in 0 until node.childCount) {
                    if (owned.size >= MAX_SCAN_NODES - 1) break
                    val child = node.getChild(i) ?: continue
                    owned.add(child)
                    queue.add(child)
                }
            }
            return HostObservation.ToolbarUnavailable
        } finally {
            owned.forEach { recycleQuietly(it) }
        }
    }

    /**
     * Node recycling is a no-op from API 33, but this service runs all day and minSdk is 26.
     * Without it the scan leaks a few hundred nodes every few seconds until the system node
     * cache is exhausted and detection quietly degrades.
     */
    @Suppress("DEPRECATION")
    private fun recycleQuietly(node: AccessibilityNodeInfo?) {
        if (node == null || Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        runCatching { node.recycle() }
    }

    companion object {
        private const val TICK_MS = 5_000L

        /** Largest gap a single flush will bank. Anything more means the device slept. */
        private const val MAX_ACCRUAL_MS = TICK_MS * 3

        private const val EXTRACT_THROTTLE_MS = 700L
        private const val FALLBACK_SCAN_INTERVAL_MS = 3_000L
        private const val BLOCK_DEBOUNCE_MS = 4_000L
        private const val MAX_SCAN_NODES = 400

        /** Whether the user has switched this service on in system settings. */
        fun isEnabled(ctx: Context): Boolean {
            val component = ComponentName(ctx, UrlWatcherService::class.java)
            val enabled = Settings.Secure.getString(
                ctx.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return enabled.split(':').any {
                it.equals(component.flattenToString(), ignoreCase = true) ||
                    it.equals(component.flattenToShortString(), ignoreCase = true)
            }
        }
    }
}
