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
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * Watches the URL bar of the browsers the user selected, accrues time against the daily
 * budget for any matching domain, and raises [BlockActivity] when the budget runs out.
 *
 * Deliberately dumb about tabs: it tracks "the host the front browser is showing", which is
 * all a productivity nudge needs.
 */
class UrlWatcherService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    // Lazy rather than lateinit: nothing here should depend on onServiceConnected having run
    // before the first event or tick arrives.
    private val prefs by lazy { Prefs(this) }
    private val power by lazy { getSystemService(Context.POWER_SERVICE) as PowerManager }

    private var browsers: Set<String> = Browsers.DEFAULT_PACKAGES

    /** Package of the window currently in front. */
    private var foregroundPkg: String? = null

    /** Host the front browser is showing, or null when we are not in a browser. */
    private var currentHost: String? = null

    /** elapsedRealtime of the last accrual; 0 means "the clock is not running". */
    private var lastAccrualMs = 0L
    private var lastExtractMs = 0L
    private var lastFallbackScanMs = 0L
    private var lastBlockMs = 0L

    /** Domains already warned about today, so the nudge fires once. */
    private val warned = mutableSetOf<String>()
    private var currentDay = ""

    private val ticker = object : Runnable {
        override fun run() {
            try {
                tick(verifyForeground = true)
            } catch (t: Throwable) {
                Log.w(TAG, "tick failed", t)
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        browsers = prefs.browsers()
        rollDayIfNeeded()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        // browsers is the candidate package list, most of which are typically not installed;
        // saying "watching N browsers" implied N real ones.
        Log.i(TAG, "connected, ${browsers.size} candidate browser packages configured")
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        try {
            stopAccruing()
        } catch (t: Throwable) {
            Log.w(TAG, "shutdown flush failed", t)
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
            Log.w(TAG, "event handling failed", t)
        }
    }

    private fun handleEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != foregroundPkg) {
            // Left whatever we were on. Bank the time before switching context.
            flush()
            foregroundPkg = pkg
            if (pkg !in browsers) {
                currentHost = null
                lastAccrualMs = 0L
            }
        }

        if (pkg !in browsers) return
        foregroundPkg = pkg

        // Content-change events fire constantly while a page renders; sample them.
        val now = SystemClock.elapsedRealtime()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            now - lastExtractMs < EXTRACT_THROTTLE_MS
        ) return
        lastExtractMs = now

        val host = extractHost(pkg)
        if (host != null && host != currentHost) {
            flush()
            currentHost = host
            if (BuildConfig.DEBUG) Log.d(TAG, "host -> $host")
        }
        // The event already told us the foreground package, so skip the extra window lookup.
        tick(verifyForeground = false)
    }

    // ---------------- time accounting ----------------

    /** Banks the time since the last accrual against whichever domain owns [currentHost]. */
    private fun flush() {
        val now = SystemClock.elapsedRealtime()
        val host = currentHost

        if (host == null || lastAccrualMs <= 0L) {
            lastAccrualMs = now
            return
        }

        val elapsed = now - lastAccrualMs
        if (elapsed <= 0L) {
            lastAccrualMs = now
            return
        }

        // elapsedRealtime keeps counting through deep sleep, and no tick runs while the CPU is
        // suspended. Without this cap, locking the phone on a page and picking it up the next
        // morning would bank the entire night against the budget. There is no way to know how
        // much of a gap this large was real reading, so bank none of it.
        if (elapsed > MAX_ACCRUAL_MS) {
            if (BuildConfig.DEBUG) Log.d(TAG, "discarding ${elapsed}ms gap")
            lastAccrualMs = now
            return
        }

        val seconds = (elapsed / 1000L).toInt()
        if (seconds <= 0) return // sub-second, let it accumulate into the next flush

        prefs.matchRule(host)?.let { prefs.addUsage(it.domain, seconds) }
        // Carry the sub-second remainder so repeated flushes do not shave time off.
        lastAccrualMs = now - (elapsed % 1000L)
    }

    private fun stopAccruing() {
        if (currentHost != null) flush()
        currentHost = null
        lastAccrualMs = 0L
    }

    /**
     * @param verifyForeground re-read the active window to confirm what is really in front.
     * Costs a binder round trip, so only the timer does it; events carry their own package.
     */
    private fun tick(verifyForeground: Boolean) {
        browsers = prefs.browsers()
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
        if (verifyForeground) activePackage()?.let { foregroundPkg = it }

        val pkg = foregroundPkg
        if (pkg == null || pkg !in browsers) {
            stopAccruing()
            return
        }

        // Re-acquire the host after any gap that cleared it.
        if (currentHost == null) currentHost = extractHost(pkg)
        val host = currentHost ?: return

        flush()

        val rule = prefs.matchRule(host) ?: return
        if (prefs.blockingSuppressed(rule.domain)) return

        val used = prefs.usedSeconds(rule.domain)
        if (used >= rule.limitSeconds) {
            block(rule, used)
        } else if (rule.limitSeconds - used <= WARN_SECONDS) {
            warnOnce(rule)
        }
    }

    /** Clears per-day state and prunes stale counters when the budget day rolls over. */
    private fun rollDayIfNeeded() {
        val day = prefs.today()
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

    private fun block(rule: Rule, usedSeconds: Int) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBlockMs < BLOCK_DEBOUNCE_MS) return
        lastBlockMs = now

        if (Settings.canDrawOverlays(this)) {
            val intent = Intent(this, BlockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(BlockActivity.EXTRA_DOMAIN, rule.domain)
                .putExtra(BlockActivity.EXTRA_USED, usedSeconds)
                .putExtra(BlockActivity.EXTRA_LIMIT, rule.limitSeconds)
            try {
                startActivity(intent)
                return
            } catch (t: Throwable) {
                // Background activity starts can still be refused. Fall through rather than
                // letting the limit silently do nothing.
                Log.w(TAG, "block screen refused", t)
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
        Log.w(TAG, "rootInActiveWindow failed", t)
        null
    }

    private fun extractHost(pkg: String): String? {
        val root = activeRoot() ?: return null
        try {
            if (root.packageName?.toString() != pkg) return null

            for (id in Browsers.urlBarIds(pkg)) {
                val nodes = root.findAccessibilityNodeInfosByViewId(id) ?: continue
                try {
                    for (node in nodes) {
                        // A focused omnibox holds what the user is typing, not the current page.
                        if (node.isFocused) continue
                        Browsers.hostFromBarText(node.text)?.let { return it }
                        Browsers.hostFromBarText(node.contentDescription)?.let { return it }
                    }
                } finally {
                    nodes.forEach { recycleQuietly(it) }
                }
            }

            // Unknown browser, or the toolbar is hidden because the page is scrolled. The scan
            // is the expensive path, so rate-limit it hard.
            val now = SystemClock.elapsedRealtime()
            if (now - lastFallbackScanMs < FALLBACK_SCAN_INTERVAL_MS) return null
            lastFallbackScanMs = now
            return scanForUrlBar(root)
        } finally {
            recycleQuietly(root)
        }
    }

    private fun scanForUrlBar(root: AccessibilityNodeInfo): String? {
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
                    !node.isFocused
                ) {
                    Browsers.hostFromBarText(node.text)?.let { return it }
                    Browsers.hostFromBarText(node.contentDescription)?.let { return it }
                }

                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    owned.add(child)
                    queue.add(child)
                }
            }
            return null
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
        private const val TAG = "SiteLimiter"
        private const val TICK_MS = 5_000L

        /** Largest gap a single flush will bank. Anything more means the device slept. */
        private const val MAX_ACCRUAL_MS = TICK_MS * 3

        private const val EXTRACT_THROTTLE_MS = 700L
        private const val FALLBACK_SCAN_INTERVAL_MS = 3_000L
        private const val BLOCK_DEBOUNCE_MS = 4_000L
        private const val MAX_SCAN_NODES = 400
        private const val WARN_SECONDS = 5 * 60

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
