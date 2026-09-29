package com.mikes.sitelimiter

import java.time.ZoneId

/**
 * The one in-memory owner of [LimitState] for the process. The activities and the
 * accessibility service all share a single instance, so a write is visible to every reader
 * immediately and reads never touch storage.
 *
 * Reads only need [LimitPolicy.refresh] once the budget period ends: snoozes and
 * off-for-today are checked against the clock at read time, and every policy call that
 * changes usage or rules recomputes locks itself.
 */
class StateStore(
    private val load: (now: Long) -> LimitState,
    private val save: (LimitState) -> Unit,
    private val zone: () -> ZoneId,
    private val saveIntervalMs: Long = SAVE_INTERVAL_MS,
) {
    private var state: LimitState? = null
    private var dirty = false
    private var lastSaveMs = 0L

    @Synchronized
    fun get(now: Long): LimitState {
        state?.let { if (now < it.endsAt) return it }
        val current = state ?: load(now)
        val refreshed = LimitPolicy.refresh(current, now, zone())
        state = refreshed
        if (refreshed != current) persist(refreshed, now)
        return refreshed
    }

    /**
     * Applies [change] and persists the result. With [batch], a change that only adds usage
     * is written at most every [saveIntervalMs]; a change to locks or the budget period is
     * always written at once, so a hard limit survives the process being killed.
     */
    @Synchronized
    fun update(now: Long, batch: Boolean = false, change: (LimitState) -> LimitState): LimitState {
        val before = get(now)
        val after = change(before)
        if (after == before) return after
        state = after
        val urgent = !batch || after.locked != before.locked || after.endsAt != before.endsAt
        if (urgent || now - lastSaveMs >= saveIntervalMs) persist(after, now) else dirty = true
        return after
    }

    /** Writes any batched usage. Cheap when there is nothing pending. */
    @Synchronized
    fun flush(now: Long) {
        if (dirty) state?.let { persist(it, now) }
    }

    private fun persist(s: LimitState, now: Long) {
        save(s)
        dirty = false
        lastSaveMs = now
    }

    companion object {
        /** Longest stretch of counted time that can be lost if the process is killed. */
        const val SAVE_INTERVAL_MS = 30_000L
    }
}
