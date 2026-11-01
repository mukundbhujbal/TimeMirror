package com.mukundbhujbal.timemirror.phoenix

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Phoenix 6.0 — shared, read-only monitoring status model (Gateway foundation).
 *
 * This file deliberately contains no Android dependencies, no persistence, no timer counters and
 * no health derivation logic. It only defines the data carried between the monitoring service
 * (the future sole publisher) and the Dashboard / Gateway (read-only consumers).
 *
 * Two independent dimensions are modelled:
 *  - [HealthState]: is the service/detection machinery operational? (freshness of evaluations)
 *  - [TrackingState]: is a monitored app currently active and being measured?
 *
 * Clock domains — never mix them:
 *  - every field ending in "RealtimeMs" is [android.os.SystemClock.elapsedRealtime] based
 *    (monotonic, suitable for freshness/age arithmetic);
 *  - [MonitoringStatus.transitionEventTimeMs] is wall-clock epoch milliseconds
 *    (the UsageStats event clock).
 *
 * A null timestamp means "no such evidence yet" and must never be read as a valid time value.
 */

/**
 * Service / detection health (independent of the current foreground app).
 */
enum class HealthState {
    /** No evidence yet (e.g. a fresh process). */
    UNKNOWN,

    /** A service start has been requested and no fresh heartbeat exists yet. */
    STARTING,

    /** The service loop is alive (fresh heartbeat) but no successful detector evaluation yet. */
    SERVICE_ALIVE,

    /** Heartbeat is fresh and the last successful detector evaluation is still fresh. */
    OPERATIONAL,

    /** Detection is impaired (usage access missing, or repeated query failures past freshness). */
    DEGRADED,

    /** The screen is off and the orchestrator loop is intentionally suspended. */
    SUSPENDED,

    /** Screen on, no heartbeat and no start in flight. */
    STOPPED
}

/**
 * Current foreground tracking state (independent of service health).
 *
 * This dimension is driven by the detector's current foreground evaluation, never by the age of
 * the last ACTIVITY_RESUMED event: a foreground app may remain active for a long time without
 * generating a new transition.
 */
enum class TrackingState {
    /** No successful evaluation yet, or no determinable foreground package. */
    UNKNOWN,

    /** The current foreground package is in the monitored set. */
    MONITORED_APP_ACTIVE,

    /** The current foreground package is known and not monitored (launcher / other app). */
    NO_MONITORED_APP_ACTIVE,

    /** A Gateway confirmation was attempted and no valid post-launch transition was observed. */
    TRACKING_NOT_CONFIRMED,

    /** The user has disabled the screen-time counter. */
    COUNTER_DISABLED
}

/**
 * Outcome of the latest UsageStats query phase only.
 *
 * [SUCCESS] includes a successful query that observed no new ACTIVITY_RESUMED event: that is a
 * normal, healthy evaluation. Fallback/verification failures are recorded separately via
 * [MonitoringStatus.evaluationException] and must not turn a completed query into a failure.
 */
enum class QueryOutcome {
    UNKNOWN,
    SUCCESS,
    EXCEPTION
}

/**
 * Immutable monitoring status snapshot.
 *
 * All "RealtimeMs" fields use the elapsedRealtime clock domain; [transitionEventTimeMs] uses the
 * wall-clock epoch-millisecond domain. Nullable timestamps represent absent evidence.
 */
data class MonitoringStatus(
    /** Process-scoped identity, used to avoid cross-process confirmation confusion. */
    val processInstanceId: String = "",
    /** Service / detection health dimension. */
    val health: HealthState = HealthState.UNKNOWN,
    /** Current foreground tracking dimension. */
    val tracking: TrackingState = TrackingState.UNKNOWN,
    /** Latest orchestrator loop heartbeat (elapsedRealtime), null if none observed. */
    val lastHeartbeatRealtimeMs: Long? = null,
    /** Completion of the latest full detector evaluation (elapsedRealtime), null if none. */
    val lastEvaluationRealtimeMs: Long? = null,
    /**
     * Completion of the latest SUCCESSFUL detector evaluation (elapsedRealtime), null if none.
     * This is the basis for successful-evaluation freshness; a successful query with no new event
     * counts. It is not reset by a later transient query exception.
     */
    val lastSuccessfulEvaluationRealtimeMs: Long? = null,
    /** Completion of the latest query phase, success or failure (elapsedRealtime). */
    val lastQueryCompletedRealtimeMs: Long? = null,
    /** Outcome of the latest query phase. */
    val lastQueryOutcome: QueryOutcome = QueryOutcome.UNKNOWN,
    /** A fallback/verification exception occurred after the query phase (diagnostic only). */
    val evaluationException: Boolean = false,
    /** Current foreground package from the detector's latch/evaluation, may be stale-looking but
     *  is the current evidence; never expired by event age. */
    val currentForegroundPackage: String? = null,
    /** Whether [currentForegroundPackage] is in the monitored set; null when unknown. */
    val currentForegroundMonitored: Boolean? = null,
    /** Process-scoped transition generation; increments only on a newly observed transition. */
    val transitionGeneration: Long = 0L,
    /** Package of the latest newly observed transition (Gateway confirmation evidence). */
    val transitionPackage: String? = null,
    /** Wall-clock epoch milliseconds of that transition event (never compare to RealtimeMs). */
    val transitionEventTimeMs: Long? = null,
    /**
     * elapsedRealtime when the detector observed the transition — not when the event occurred.
     * Used to prove observation happened after a Gateway launch boundary.
     */
    val transitionObservedRealtimeMs: Long? = null,
    /**
     * True when the transition's chronological ordering cannot be trusted for Gateway
     * confirmation (same-timestamp ambiguity or clock-regression evidence).
     */
    val transitionOrderingAmbiguous: Boolean = false,
    /** Conservative composite identity of that transition (e.g. "timeStamp|package"). */
    val transitionIdentity: String? = null,
    /** Snapshot of PowerManager.isInteractive at publish time; null when unknown. */
    val screenInteractive: Boolean? = null,
    /** Snapshot of the UsageStats permission state at publish time; null when unknown. */
    val usageAccessGranted: Boolean? = null,
    /** Optional diagnostic message for the latest failure, never user-facing verbatim. */
    val lastError: String? = null
) {
    companion object {
        /** Default snapshot with no evidence at all. */
        val INITIAL = MonitoringStatus()
    }
}

/**
 * Process-scoped holder for the single shared status snapshot.
 *
 * The monitoring service is the only intended publisher (later stage). Dashboard and Gateway must
 * only read [status]. No first detector, timer engine, service manager, coroutine or persistence
 * is introduced here.
 */
object MonitoringStatusStore {

    private val _status = MutableStateFlow(MonitoringStatus.INITIAL)

    /** Read-only stream for consumers. The mutable flow is never exposed. */
    val status: StateFlow<MonitoringStatus> = _status.asStateFlow()

    /**
     * Replaces the snapshot. Intended to be called by the monitoring service only (Stage 3
     * integration); consumers must not call this. Internal is the narrowest enforceable
     * visibility inside this single-module project.
     */
    internal fun publish(snapshot: MonitoringStatus) {
        _status.value = snapshot
    }

    /**
     * Atomically derives a new snapshot from the current one. Also service-only; provided for
     * incremental publication of single fields without rebuilding the whole snapshot.
     */
    internal fun update(transform: (MonitoringStatus) -> MonitoringStatus) {
        _status.update(transform)
    }
}
