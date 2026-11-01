package com.mukundbhujbal.timemirror.detector

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.SystemClock
import com.mukundbhujbal.timemirror.phoenix.QueryOutcome
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Detects the currently active foreground application using UsageStatsManager.
 * Retains active package state between polls to handle continuous usage sessions
 * and includes debounce handling for rapid app switching.
 *
 * Stage 2 (Gateway foundation): additive, read-only evidence instrumentation only.
 * The detection algorithm, latch semantics, fallback/verification paths, throttling, grace
 * periods and the returned results are unchanged. Evidence is exposed through
 * [currentForeground] and [evidenceSnapshot] and is not published anywhere yet.
 *
 * Clock domains:
 *  - every "RealtimeMs" field below is [SystemClock.elapsedRealtime] based;
 *  - [DetectorEvidence.transitionEventTimeMs] is the UsageStats wall-clock epoch-ms event clock.
 *  - the two domains are never compared.
 */
class ForegroundAppDetector(private val context: Context) {

    private val usageStatsManager: UsageStatsManager? =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager

    @Volatile
    private var currentForegroundPackage: String? = null

    @Volatile
    private var lastMonitoredPackage: String? = null

    @Volatile
    private var lastMonitoredExitTimestamp: Long = 0L

    @Volatile
    private var lastKnownMonitoredPackages: Set<String> = emptySet()

    @Volatile
    private var lastVerificationRealtime: Long = 0L

    private val selfPackageName: String = context.packageName

    /**
     * Queries recent system events and returns the current top foreground package.
     */
    fun detectForegroundPackage(): String? = detectForegroundPackage(lastKnownMonitoredPackages)

    /**
     * Overloaded query taking currently monitored packages to enable
     * self-correction of unmonitored stale latches when event windows elapse.
     */
    fun detectForegroundPackage(monitoredPackages: Set<String>): String? {
        lastKnownMonitoredPackages = monitoredPackages

        // Correction C: diagnostics describe this evaluation only; an old exception must not
        // linger forever after a later successful evaluation.
        lastEvaluationException = false
        lastExceptionMessage = null

        // Correction D: when UsageStatsManager is unavailable, record a failed evaluation
        // (never a successful query) without erasing the previous successful timestamp, and
        // preserve the existing caller-visible null return.
        val usm = usageStatsManager
        if (usm == null) {
            lastQueryOutcome = QueryOutcome.EXCEPTION
            lastQueryCompletedRealtimeMs = SystemClock.elapsedRealtime()
            lastEvaluationRealtimeMs = lastQueryCompletedRealtimeMs
            lastEvaluationException = true
            lastExceptionMessage = "UsageStatsManager unavailable"
            return null
        }

        val now = System.currentTimeMillis()
        val startTime = now - EVENT_LOOKBACK_WINDOW_MS

        // Additive evidence: distinguishes a failure of the query phase itself from a later
        // fallback/verification failure. False until queryEvents returns successfully.
        var queryPhaseSucceeded = false

        try {
            val events = usm.queryEvents(startTime, now)

            // The query phase completed successfully. This is recorded before any interpretation,
            // so a successful query with no new ACTIVITY_RESUMED event still counts as SUCCESS.
            queryPhaseSucceeded = true
            lastQueryOutcome = QueryOutcome.SUCCESS
            lastQueryCompletedRealtimeMs = SystemClock.elapsedRealtime()
            lastSuccessfulEvaluationRealtimeMs = lastQueryCompletedRealtimeMs

            val event = UsageEvents.Event()

            var latestResumeEventTime = 0L
            var latestResumePackage: String? = null

            // Correction A: detect same-timestamp/different-package ambiguity inside this scan.
            // The selection rule is unchanged (the last iterated event at the maximum timestamp
            // wins); the flag is evidence only and never changes which package is selected.
            var withinScanAmbiguous = false

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    val selectedPackage = latestResumePackage
                    when {
                        selectedPackage == null -> {
                            latestResumeEventTime = event.timeStamp
                            latestResumePackage = event.packageName
                            withinScanAmbiguous = false
                        }
                        event.timeStamp > latestResumeEventTime -> {
                            latestResumeEventTime = event.timeStamp
                            latestResumePackage = event.packageName
                            withinScanAmbiguous = false
                        }
                        event.timeStamp == latestResumeEventTime -> {
                            if (event.packageName != selectedPackage) {
                                withinScanAmbiguous = true
                            }
                            // Preserve the existing ">=" selection: last iterated wins.
                            latestResumePackage = event.packageName
                        }
                        // Older timestamp: never changes the selection or the ambiguity flag.
                    }
                }
            }

            // Additive evidence: record a newly observed foreground transition. This does not
            // affect the latch; it only feeds the conservative process-scoped transition ledger.
            if (latestResumePackage != null) {
                TransitionLedger.record(
                    eventType = UsageEvents.Event.ACTIVITY_RESUMED,
                    packageName = latestResumePackage!!,
                    eventTimeMs = latestResumeEventTime,
                    observedRealtimeMs = SystemClock.elapsedRealtime(),
                    scanAmbiguous = withinScanAmbiguous
                )
            }

            if (latestResumePackage != null) {
                currentForegroundPackage = latestResumePackage
            } else if (currentForegroundPackage == null) {
                // Fallback on initial launch if no recent resume events in the 10s window
                val verificationStartTime = now - USAGE_STATS_VERIFICATION_WINDOW_MS
                val stats = usm.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY,
                    verificationStartTime,
                    now
                )
                val mostRecent = stats
                    ?.filter { it.lastTimeUsed > 0L }
                    ?.maxByOrNull { it.lastTimeUsed }
                if (mostRecent != null) {
                    currentForegroundPackage = mostRecent.packageName
                }
            } else if (monitoredPackages.isNotEmpty() && !monitoredPackages.contains(currentForegroundPackage)) {
                // Stale unmonitored latch detection:
                // When no recent ACTIVITY_RESUMED event occurred in the 10s window and the detector
                // is currently latched to an unmonitored package (e.g. launcher), perform a throttled
                // wider UsageStats verification to determine if a monitored app is active.
                val nowRealtime = SystemClock.elapsedRealtime()
                if (nowRealtime - lastVerificationRealtime >= VERIFICATION_THROTTLE_MS) {
                    lastVerificationRealtime = nowRealtime
                    val verificationStartTime = now - USAGE_STATS_VERIFICATION_WINDOW_MS
                    val stats = usm.queryUsageStats(
                        UsageStatsManager.INTERVAL_DAILY,
                        verificationStartTime,
                        now
                    )
                    if (!stats.isNullOrEmpty()) {
                        val overallMostRecent = stats
                            .filter { it.lastTimeUsed > 0L }
                            .maxByOrNull { it.lastTimeUsed }

                        if (overallMostRecent != null) {
                            val mostRecentPkg = overallMostRecent.packageName
                            val currentStalePkg = currentForegroundPackage
                            val staleLastUsed = stats
                                .filter { it.packageName == currentStalePkg }
                                .maxOfOrNull { it.lastTimeUsed } ?: 0L

                            if (monitoredPackages.contains(mostRecentPkg) && overallMostRecent.lastTimeUsed > staleLastUsed) {
                                currentForegroundPackage = mostRecentPkg
                            } else if (mostRecentPkg != currentStalePkg && overallMostRecent.lastTimeUsed > staleLastUsed) {
                                currentForegroundPackage = mostRecentPkg
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Existing behavior preserved: the caller still receives the current latch value.
            // Additive evidence: a query-phase failure is recorded as EXCEPTION; a later
            // fallback/verification failure is recorded diagnostically and must not overwrite an
            // already successful query phase.
            if (queryPhaseSucceeded) {
                lastEvaluationException = true
                lastExceptionMessage = e.message
            } else {
                lastQueryOutcome = QueryOutcome.EXCEPTION
                lastQueryCompletedRealtimeMs = SystemClock.elapsedRealtime()
                lastExceptionMessage = e.message
            }
        } finally {
            lastEvaluationRealtimeMs = SystemClock.elapsedRealtime()
        }

        return currentForegroundPackage
    }

    /**
     * Determines whether the detected package is a monitored external app.
     * Incorporates a transition grace period (800ms) when switching directly
     * between monitored apps to ensure continuous timer operation.
     */
    fun isMonitoredAppActive(monitoredPackages: Set<String>): Boolean {
        lastKnownMonitoredPackages = monitoredPackages
        val activePackage = detectForegroundPackage(monitoredPackages)

        // Ignore self (TimeMirror) as external monitored app
        if (activePackage == selfPackageName || activePackage == null) {
            val nowRealtime = SystemClock.elapsedRealtime()
            // Grace period check for direct task switching
            if (lastMonitoredPackage != null && (nowRealtime - lastMonitoredExitTimestamp) < TRANSITION_GRACE_PERIOD_MS) {
                return true
            }
            return false
        }

        val isMonitored = monitoredPackages.contains(activePackage)

        if (isMonitored) {
            lastMonitoredPackage = activePackage
            lastMonitoredExitTimestamp = SystemClock.elapsedRealtime()
            return true
        } else {
            val nowRealtime = SystemClock.elapsedRealtime()
            if (lastMonitoredPackage != null && (nowRealtime - lastMonitoredExitTimestamp) < TRANSITION_GRACE_PERIOD_MS) {
                return true
            }
            lastMonitoredPackage = null
            return false
        }
    }

    /**
     * Read-only access to the existing current-foreground latch.
     *
     * Returns the latch value only; it never performs a UsageStats query and never changes the
     * latch. This is the "current foreground evidence" used for ongoing tracking; it must not be
     * expired merely because the last ACTIVITY_RESUMED event is old.
     */
    fun currentForeground(): String? = currentForegroundPackage

    /**
     * Immutable, read-only snapshot of the additive evidence. Safe to call from the service; it
     * does not query UsageStats and does not mutate any state.
     *
     * The transition fields come from a single immutable record published atomically, so readers
     * never observe a new generation with an old package/timestamp combination.
     */
    internal fun evidenceSnapshot(): DetectorEvidence {
        val transition = TransitionLedger.current()
        return DetectorEvidence(
            processInstanceId = TransitionLedger.processInstanceId,
            queryOutcome = lastQueryOutcome,
            lastQueryCompletedRealtimeMs = lastQueryCompletedRealtimeMs,
            lastSuccessfulEvaluationRealtimeMs = lastSuccessfulEvaluationRealtimeMs,
            lastEvaluationRealtimeMs = lastEvaluationRealtimeMs,
            evaluationException = lastEvaluationException,
            exceptionMessage = lastExceptionMessage,
            currentForegroundPackage = currentForegroundPackage,
            transitionGeneration = transition.generation,
            transitionPackage = transition.packageName,
            transitionEventTimeMs = transition.eventTimeMs,
            transitionObservedRealtimeMs = transition.observedRealtimeMs,
            transitionOrderingAmbiguous = transition.orderingAmbiguous,
            transitionIdentity = transition.identity
        )
    }

    companion object {
        private const val EVENT_LOOKBACK_WINDOW_MS = 10000L // 10-second lookback window
        private const val USAGE_STATS_VERIFICATION_WINDOW_MS = 60 * 60 * 1000L // 1-hour verification window
        private const val VERIFICATION_THROTTLE_MS = 3000L // 3-second throttle for wider verification
        private const val TRANSITION_GRACE_PERIOD_MS = 800L // 800ms transition grace period

        // --- Additive, process-scoped evidence (never timer state, never persistence) ---

        /** Outcome of the latest query phase only. */
        @Volatile
        private var lastQueryOutcome: QueryOutcome = QueryOutcome.UNKNOWN

        /** Latest query-phase completion, success or exception (elapsedRealtime). */
        @Volatile
        private var lastQueryCompletedRealtimeMs: Long? = null

        /** Latest SUCCESSFUL query-phase completion (elapsedRealtime); null when none. */
        @Volatile
        private var lastSuccessfulEvaluationRealtimeMs: Long? = null

        /** Completion of the whole detector call (elapsedRealtime). */
        @Volatile
        private var lastEvaluationRealtimeMs: Long? = null

        /** A fallback/verification exception occurred after a successful query (diagnostic only). */
        @Volatile
        private var lastEvaluationException: Boolean = false

        /** Diagnostic message for the latest exception; never user-facing verbatim. */
        @Volatile
        private var lastExceptionMessage: String? = null
    }
}

/**
 * Immutable detector evidence snapshot (internal to the app module).
 *
 * "RealtimeMs" fields use [SystemClock.elapsedRealtime]; [transitionEventTimeMs] uses the
 * wall-clock epoch-millisecond domain. The two must never be compared.
 *
 * Limitation: the transition identity ("eventType|package|timestamp") cannot distinguish two
 * genuinely distinct ACTIVITY_RESUMED events that share the same type, package and timestamp;
 * such events are conservatively treated as one. An equal-timestamp, different-package
 * observation is recorded but flagged [DetectorEvidence.transitionOrderingAmbiguous] because its
 * ordering cannot be established; Stage 3 must treat ambiguous records as unconfirmable. A lesser
 * timestamp (wall-clock regression) is ignored, which can only cause a missed confirmation.
 */
internal data class DetectorEvidence(
    val processInstanceId: String,
    val queryOutcome: QueryOutcome,
    val lastQueryCompletedRealtimeMs: Long?,
    val lastSuccessfulEvaluationRealtimeMs: Long?,
    val lastEvaluationRealtimeMs: Long?,
    val evaluationException: Boolean,
    val exceptionMessage: String?,
    val currentForegroundPackage: String?,
    val transitionGeneration: Long,
    val transitionPackage: String?,
    val transitionEventTimeMs: Long?,
    val transitionObservedRealtimeMs: Long?,
    val transitionOrderingAmbiguous: Boolean,
    val transitionIdentity: String?
)

/** Immutable transition record; published atomically as one unit. */
private data class TransitionRecord(
    val generation: Long,
    val packageName: String?,
    /** Event occurrence time — wall-clock epoch milliseconds (UsageStats event clock). */
    val eventTimeMs: Long?,
    /** When this record was observed — elapsedRealtime (monotonic). Never compare with eventTimeMs. */
    val observedRealtimeMs: Long?,
    val identity: String?,
    /** True when two events share a timestamp and their relative ordering cannot be established. */
    val orderingAmbiguous: Boolean
)

/**
 * Process-scoped transition ledger.
 *
 * Shared by all detector instances in the same process, so service recreation does not reset the
 * generation while the process is alive. The generation is carried inside the immutable record,
 * which is published via a single [AtomicReference] compare-and-set: readers never see a new
 * generation combined with an old package/timestamp.
 *
 * Occurrence (wall-clock event time) and observation (elapsedRealtime record time) are stored
 * separately, so Stage 3 can validate an event against its own launch boundary instead of
 * assuming that "discovered after launch" means "occurred after launch".
 *
 * The process instance id is generated once per process.
 */
private object TransitionLedger {

    val processInstanceId: String = UUID.randomUUID().toString()

    private val latest = AtomicReference(
        TransitionRecord(
            generation = 0L,
            packageName = null,
            eventTimeMs = null,
            observedRealtimeMs = null,
            identity = null,
            orderingAmbiguous = false
        )
    )

    fun current(): TransitionRecord = latest.get()

    /**
     * Records the latest ACTIVITY_RESUMED observation using conservative comparison rules:
     *  - greater timestamp                    -> NEW transition (ambiguous only when the scan saw
     *    different packages at that same maximum timestamp)
     *  - equal timestamp, different package   -> NEW record, flagged orderingAmbiguous
     *    (two events share a timestamp; their relative order cannot be established)
     *  - equal timestamp, same package        -> same observed event, no increment
     *  - lesser timestamp (clock regression)  -> ignored; no new transition
     *
     * Ambiguity is evidence for Gateway confirmation only and never alters the detector's
     * selected package or latch.
     *
     * [scanAmbiguous] carries within-scan ambiguity (different packages at the same maximum
     * timestamp in the current query result). For an already recorded transition identity, a
     * later scan may only SET ambiguity; it never clears it and never increments the generation.
     */
    fun record(
        eventType: Int,
        packageName: String,
        eventTimeMs: Long,
        observedRealtimeMs: Long,
        scanAmbiguous: Boolean
    ) {
        val identity = "$eventType|$packageName|$eventTimeMs"
        while (true) {
            val previous = latest.get()

            // Same previously observed transition: update ambiguity evidence only. This never
            // increments the generation and never clears an existing ambiguity flag.
            if (previous.identity == identity) {
                if (scanAmbiguous && !previous.orderingAmbiguous) {
                    val updated = previous.copy(orderingAmbiguous = true)
                    if (latest.compareAndSet(previous, updated)) return
                    continue
                }
                return
            }

            val previousTimeMs = previous.eventTimeMs
            var isNewTransition = false
            var orderingAmbiguous = scanAmbiguous
            when {
                previousTimeMs == null -> isNewTransition = true
                eventTimeMs > previousTimeMs -> isNewTransition = true
                eventTimeMs == previousTimeMs && previous.packageName != packageName -> {
                    isNewTransition = true
                    orderingAmbiguous = true
                }
                else -> Unit // older/clock-anomalous event: no new transition
            }
            if (!isNewTransition) return

            val next = TransitionRecord(
                generation = previous.generation + 1,
                packageName = packageName,
                eventTimeMs = eventTimeMs,
                observedRealtimeMs = observedRealtimeMs,
                identity = identity,
                orderingAmbiguous = orderingAmbiguous
            )
            if (latest.compareAndSet(previous, next)) return
        }
    }
}
