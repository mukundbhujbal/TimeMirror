package com.mukundbhujbal.timemirror.engine

/**
 * Pure, Android-free accumulator that converts measured monotonic intervals
 * (milliseconds) into whole credited seconds, carrying the sub-second remainder
 * forward so repeated fractional intervals are not truncated away.
 *
 * C1 — timer accuracy:
 * The authoritative counter used to advance by a fixed "+1" per orchestrator loop
 * iteration, which under-counted real foreground time whenever an iteration took
 * longer than one second (loop work, scheduler delay, process stalls). The
 * orchestrator now feeds the measured `SystemClock.elapsedRealtime()` delta
 * through this accumulator instead.
 *
 * This class owns no clock and no persistent state. The remainder is deliberately
 * process-local: losing it on process death costs at most one second, and keeping
 * it out of `SharedPreferences` avoids changing any existing key or unit.
 *
 * @param maxCreditMillis upper bound applied to a single interval. A nominal
 *   iteration is ~1000 ms, so this bound absorbs transient stalls (GC, binder,
 *   scheduler) while preventing a long process freeze or suspension window from
 *   being credited as observed tracked time. It is a deliberate safety margin and
 *   NOT an empirically measured value; see [MAX_CREDIT_MILLIS].
 */
class ElapsedCredit(private val maxCreditMillis: Long = MAX_CREDIT_MILLIS) {

    init {
        require(maxCreditMillis > 0L) { "maxCreditMillis must be positive" }
    }

    private var pendingMillis: Long = 0L

    /** Sub-second remainder not yet converted into credited seconds. */
    fun pendingMillis(): Long = pendingMillis

    /**
     * Discards any carried remainder. Call when the next interval must not be
     * allowed to inherit uncredited time: screen off, no monitored app in the
     * foreground, and midnight rollover.
     */
    fun reset() {
        pendingMillis = 0L
    }

    /**
     * Consumes one measured interval.
     *
     * @param deltaMillis measured monotonic time since the previous interval.
     * @param credit true only when this interval may be credited (screen
     *   interactive AND a monitored app is in the foreground).
     * @return whole seconds to credit for this interval; `0` when [credit] is
     *   false, when [deltaMillis] is not positive, or while the accumulated
     *   remainder is still below one second.
     */
    fun onInterval(deltaMillis: Long, credit: Boolean): Long {
        if (!credit) {
            reset()
            return 0L
        }
        if (deltaMillis <= 0L) return 0L
        pendingMillis += deltaMillis.coerceAtMost(maxCreditMillis)
        val wholeSeconds = pendingMillis / 1000L
        pendingMillis -= wholeSeconds * 1000L
        return wholeSeconds
    }

    companion object {
        /**
         * Safety bound for a single credited interval. The nominal iteration period
         * is 1000 ms; 5000 ms gives 5x headroom for ordinary jitter while bounding
         * what a freeze/suspension can add in one step.
         *
         * Trade-off: a genuine stall longer than this bound under-counts the excess
         * (the conservative direction). If on-device measurement later shows that
         * normal iterations approach this value, the constant should be revisited.
         */
        const val MAX_CREDIT_MILLIS = 5000L
    }
}
