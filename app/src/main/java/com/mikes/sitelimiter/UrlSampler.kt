package com.mikes.sitelimiter

/** Content events may be throttled; timer and window-change samples always run. */
class UrlSampler(private val throttleMs: Long = 700L) {
    private var lastSampleMs: Long? = null

    fun sample(nowMs: Long, throttle: Boolean = false, readWindow: () -> Unit): Boolean {
        val last = lastSampleMs
        if (throttle && last != null && nowMs - last in 0 until throttleMs) return false
        readWindow()
        lastSampleMs = nowMs
        return true
    }
}
