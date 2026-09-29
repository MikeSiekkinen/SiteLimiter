package com.mikes.sitelimiter

import android.util.Log

/** Only fixed events and code locations may cross the app's logging boundary. */
internal object PrivacyLog {
    enum class Event { CONNECTED, TICK_FAILED, FLUSH_FAILED, EVENT_FAILED, BLOCK_FAILED, ROOT_FAILED, BLANK_TAB_FAILED, BROWSER_EXIT_CLICK, BROWSER_EXIT_HOME }

    fun info(event: Event) { Log.i("SiteLimiter", event.name) }

    fun warning(event: Event, error: Throwable) {
        // Never pass Throwable to Log: its message, causes and suppressed exceptions may
        // contain URLs, view text, intent extras or other user-controlled data.
        val frames = error.stackTrace.take(12).joinToString("\n") { frame ->
            "at ${frame.className}.${frame.methodName}:${frame.lineNumber}"
        }
        Log.w("SiteLimiter", "${event.name}\n$frames")
    }
}
