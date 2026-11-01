package com.mukundbhujbal.timemirror.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mukundbhujbal.timemirror.MainActivity
import com.mukundbhujbal.timemirror.R
import com.mukundbhujbal.timemirror.data.AppPreferences
import com.mukundbhujbal.timemirror.data.HistoryRepository
import com.mukundbhujbal.timemirror.data.RecoverySnapshot
import com.mukundbhujbal.timemirror.detector.ForegroundAppDetector
import com.mukundbhujbal.timemirror.engine.ElapsedCredit
import com.mukundbhujbal.timemirror.engine.TimerEngine
import com.mukundbhujbal.timemirror.overlay.OverlayController
import com.mukundbhujbal.timemirror.phoenix.HealthState
import com.mukundbhujbal.timemirror.phoenix.MonitoringStatus
import com.mukundbhujbal.timemirror.phoenix.MonitoringStatusStore
import com.mukundbhujbal.timemirror.phoenix.PhoenixAlarmManager
import com.mukundbhujbal.timemirror.phoenix.PhoenixHealthMonitor
import com.mukundbhujbal.timemirror.phoenix.QueryOutcome
import com.mukundbhujbal.timemirror.phoenix.TrackingState
import com.mukundbhujbal.timemirror.ui.PermissionHelper
import com.mukundbhujbal.timemirror.widget.WidgetRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Foreground Service that continuously orchestrates:
 * 1. Foreground app detection
 * 2. Unified TimerEngine & Daily History tracking with midnight reset
 * 3. OverlayController watermark positioning and visibility
 * 4. Persistent notification updates
 */
class TimerOverlayService : Service() {

    private lateinit var appPreferences: AppPreferences
    private lateinit var foregroundDetector: ForegroundAppDetector
    private lateinit var overlayController: OverlayController
    private lateinit var historyRepository: HistoryRepository

    // M2: SupervisorJob keeps the scope restartable when an individual loop fails; note
    // that it does NOT by itself stop an uncaught exception from reaching Android's
    // default handler, so the loop body still handles its own recoverable failures.
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var orchestratorJob: Job? = null
    private var isScreenReceiverRegistered = false

    // F3: set when both foreground-promotion attempts fail in onCreate(). Main-thread only
    // (all Service lifecycle callbacks run on the main thread). While set, this instance never
    // reports a sticky start and initialises nothing further.
    private var foregroundPromotionFailed = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    // 1. Capture current values while state is active
                    val isMonitoring = appPreferences.isMonitoringActive()
                    val isHideTimerActive = appPreferences.isTimerTemporarilyHidden()
                    val hideTimerRemainingSec = appPreferences.getRemainingHideDurationSeconds()
                    val isTimerRunning = TimerEngine.isRunning()
                    val timerElapsedSec = TimerEngine.getElapsedTodaySeconds()
                    val screenOffTimestamp = System.currentTimeMillis()

                    // 2. Flush current in-memory tracking counters to AppPreferences.
                    // Stage 3.2.3A: the snapshot carries the date its counters belong to, so a
                    // midnight rollover can never persist yesterday's counters under today's key.
                    val today = AppPreferences.getTodayDateString()
                    // M1/M3: one consistent snapshot (date + counters + map) under stateLock.
                    val countersSnapshot = snapshotLocked()
                    val snapshotDate = countersSnapshot.trackingDate
                    if (snapshotDate.isNotEmpty() && snapshotDate == today) {
                        // H1-F1: ordinary flush -- never writes the stopped-time key.
                        appPreferences.saveTodayHistoryCounters(
                            countersSnapshot.withTimerSec,
                            countersSnapshot.withoutTimerSec,
                            snapshotDate
                        )
                        appPreferences.saveTodayAppUsageMap(countersSnapshot.appUsage)
                    } else {
                        // The counters belong to another tracking date (rollover in flight) or the
                        // date is not established yet: never relabel them with today's key.
                        Log.w(
                            TAG,
                            "Screen off: skipping counter flush (snapshot date='$snapshotDate', today='$today')"
                        )
                    }

                    // 3. Save valid RecoverySnapshot
                    val snapshot = RecoverySnapshot(
                        monitoringActive = isMonitoring,
                        hideTimerActive = isHideTimerActive,
                        hideTimerRemainingSec = hideTimerRemainingSec,
                        timerRunning = isTimerRunning,
                        timerElapsedSec = timerElapsedSec,
                        screenOffTimestamp = screenOffTimestamp,
                        valid = true
                    )
                    appPreferences.saveRecoverySnapshot(snapshot)
                    Log.d(TAG, "Screen off: captured recovery snapshot (valid=true, elapsed=${timerElapsedSec}s)")

                    // 4. Invalidate the current orchestrator run atomically with publication, then
                    // enter the existing P0 quiescence. Any coroutine already past its last
                    // generation check can no longer record a heartbeat or overwrite SUSPENDED.
                    synchronized(publicationLock) {
                        orchestratorRunGeneration += 1
                    }
                    TimerEngine.pause()
                    overlayController.hide()
                    orchestratorJob?.cancel()
                    orchestratorJob = null

                    // 5. Arm Phoenix exact alarm ONLY if monitoring is active
                    if (isMonitoring) {
                        val triggerAtMillis = screenOffTimestamp + PHOENIX_ALARM_DELAY_MILLIS
                        when (PhoenixAlarmManager.schedule(this@TimerOverlayService, triggerAtMillis)) {
                            PhoenixAlarmManager.ScheduleResult.EXACT ->
                                Log.d(TAG, "Screen off: armed Phoenix watchdog (EXACT, triggerAt=$triggerAtMillis)")
                            PhoenixAlarmManager.ScheduleResult.INEXACT ->
                                Log.i(TAG, "Screen off: armed Phoenix watchdog with INEXACT timing (exact-alarm access unavailable, triggerAt=$triggerAtMillis)")
                            PhoenixAlarmManager.ScheduleResult.FAILED ->
                                Log.e(TAG, "Screen off: unable to arm Phoenix watchdog (exact and inexact scheduling failed)")
                        }
                    } else {
                        Log.d(TAG, "Screen off: monitoring is OFF -> Phoenix exact alarm not armed")
                    }

                    // Stage 3: the orchestrator is intentionally suspended while the screen is
                    // off; publish SUSPENDED (never STOPPED) for this designed state.
                    publishMonitoringStatus()
                }
                Intent.ACTION_SCREEN_ON -> {
                    if (orchestratorJob == null || orchestratorJob?.isActive == false) {
                        startOrchestratorLoop()
                    }
                    // Stage 3: the loop is resuming; STARTING until a fresh heartbeat/evaluation.
                    publishMonitoringStatus(startup = true)
                }
            }
        }
    }

    // --- M1/M3: shared timers state, guarded by stateLock ---
    // The counter pair, the app-usage map and the tracking date are mutated by the orchestrator
    // loop (Dispatchers.Default) and read by main-thread callbacks (SCREEN_OFF receiver,
    // onDestroy, onCreate). Every access goes through stateLock, and every consumer
    // takes ONE consistent snapshot before doing any I/O.
    private val stateLock = Any()
    private var todayWithTimerSec = 0L
    private var todayWithoutTimerSec = 0L
    private val todayAppUsageMap = mutableMapOf<String, Long>()

    // --- M2: orchestrator loop ownership ---
    // Serialises loop bodies so a cancelled predecessor's non-suspending tail cannot
    // overlap a replacement loop. Owned exclusively by the orchestrator coroutine;
    // main-thread lifecycle callbacks must never acquire it (they only cancel/launch).
    private val loopMutex = Mutex()

    /**
     * Tracking date of the in-memory counters. Protected by [stateLock] together with the
     * counters and the app-usage map, so a date can never be observed separately from the
     * values it describes.
     */
    private var currentTrackingDate = ""

    // Loop-confined flush bookkeeping: written only by the orchestrator loop.
    private var lastSavedSeconds = 0L
    private var lastSavedRealtimeMs = 0L

    // Widget 1 refresh bookkeeping: loop-confined, no locks. -1/null force a refresh on the
    // first tick so a restarted service always renders the current state once.
    private var lastWidgetMinute = -1L
    private var lastTrackingActive: Boolean? = null

    /**
     * One consistent cut of the tracking date, the counter pair and a defensive copy of the
     * app-usage map. The date is captured in the same [stateLock] critical section as the
     * counters, so a caller can never pair a date with counters from a different day.
     */
    private data class CountersSnapshot(
        val trackingDate: String,
        val withTimerSec: Long,
        val withoutTimerSec: Long,
        val appUsage: Map<String, Long>
    )

    /** Takes a consistent snapshot of the date + counters + map under [stateLock]. */
    private fun snapshotLocked(): CountersSnapshot = synchronized(stateLock) {
        CountersSnapshot(
            trackingDate = currentTrackingDate,
            withTimerSec = todayWithTimerSec,
            withoutTimerSec = todayWithoutTimerSec,
            appUsage = HashMap(todayAppUsageMap)
        )
    }

    /** Resets the day's counters and map and assigns the new tracking date atomically under
     * [stateLock]. Reset and date assignment must never be observable separately. */
    private fun resetCountersLocked(trackingDate: String) {
        synchronized(stateLock) {
            todayWithTimerSec = 0L
            todayWithoutTimerSec = 0L
            todayAppUsageMap.clear()
            currentTrackingDate = trackingDate
        }
    }

    /**
     * Best-effort Widget 1 refresh. Runs on the orchestrator thread; a widget failure must never
     * interrupt the monitoring loop.
     */
    private fun refreshScreenTimeWidget() {
        try {
            WidgetRenderer.refreshScreenTime(this)
        } catch (e: Exception) {
            Log.w(TAG, "Screen-time widget refresh failed", e)
        }
    }

    // --- Stage 3: shared monitoring status publication (this service is the sole publisher) ---

    // Stage 3.1: serialises snapshot construction + publication so an older snapshot can never
    // overwrite a newer one, and a destruction-time final status can never be overwritten.
    private val publicationLock = Any()

    /** Set at the very start of onDestroy(); blocks any later heartbeat or non-final publish. */
    @Volatile
    private var isDestroying = false

    /**
     * Monotonically increasing orchestrator run generation (Correction B). It is incremented
     * together with the new run's lifecycle boundary under [publicationLock]; each coroutine
     * captures its own generation and may publish only while it is still the current one.
     */
    @Volatile
    private var orchestratorRunGeneration = 0L

    /**
     * Publishes the current monitoring status to [MonitoringStatusStore].
     *
     * Informational only: it never gates the timer, never performs a UsageStats query, and any
     * failure is swallowed so status publication can never cancel the orchestrator loop or crash
     * the service.
     *
     * @param startup true when a start/resume is in progress (onCreate, screen-on, start request);
     *                the state is STARTING until a post-boundary evaluation and heartbeat exist.
     * @param finalState non-null only for the destruction-time publication (STOPPED/SUSPENDED);
     *                it bypasses the destroy guard because it is the final publication.
     */
    private fun publishMonitoringStatus(startup: Boolean = false, finalState: HealthState? = null) {
        synchronized(publicationLock) {
            // Once destruction has begun, only the final publication may still write.
            if (isDestroying && finalState == null) return
            publishMonitoringStatusLocked(
                startup = startup,
                finalState = finalState,
                currentRunSuccessRealtimeMs = null
            )
        }
    }

    /**
     * Correction C: returns the successful-evaluation timestamp only when THIS tick completed a
     * successful detector evaluation after the run boundary.
     *
     * Proof of run ownership: the global detector success timestamp must have ADVANCED during this
     * tick compared with [successRealtimeBeforeTick]. That advancement can only be produced by
     * this tick's own detector call, and the publish path additionally verifies the run generation
     * under [publicationLock]; a stale value left by an obsolete run cannot qualify. The boundary
     * comparison is strict (>) to stay conservative on millisecond equality.
     */
    private fun currentRunSuccessAfterTick(
        successRealtimeBeforeTick: Long?,
        runBoundaryRealtimeMs: Long
    ): Long? {
        val evidence = foregroundDetector.evidenceSnapshot()
        val successAfterTick = evidence.lastSuccessfulEvaluationRealtimeMs
        val queryCompletedRealtimeMs = evidence.lastQueryCompletedRealtimeMs
        val advanced = successAfterTick != null &&
                (successRealtimeBeforeTick == null || successAfterTick > successRealtimeBeforeTick)
        return if (evidence.queryOutcome == QueryOutcome.SUCCESS &&
            queryCompletedRealtimeMs != null && queryCompletedRealtimeMs > runBoundaryRealtimeMs &&
            successAfterTick != null && successAfterTick > runBoundaryRealtimeMs &&
            advanced
        ) {
            successAfterTick
        } else {
            null
        }
    }

    /** Builds and publishes one complete snapshot. Callers must hold [publicationLock]. */
    private fun publishMonitoringStatusLocked(
        startup: Boolean,
        finalState: HealthState?,
        currentRunSuccessRealtimeMs: Long?
    ) {
        try {
            val evidence = foregroundDetector.evidenceSnapshot()
            val nowRealtimeMs = SystemClock.elapsedRealtime()
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            val screenInteractive = powerManager?.isInteractive ?: true
            val heartbeatFresh = PhoenixHealthMonitor.isHealthy(
                thresholdMillis = HEALTH_FRESHNESS_MILLIS,
                nowRealtime = nowRealtimeMs
            )
            val usageAccessGranted = PermissionHelper.hasUsageStatsPermission(this)
            val monitoringActive = appPreferences.isMonitoringActive()
            val monitoredPackages = appPreferences.getMonitoredPackages()

            // Correction C: only a successful evaluation completed by the CURRENT run qualifies.
            // The caller passes the run-scoped marker (null for lifecycle publications).
            val runSuccessFresh = currentRunSuccessRealtimeMs != null &&
                    currentRunSuccessRealtimeMs > 0L &&
                    (nowRealtimeMs - currentRunSuccessRealtimeMs) in 0L..HEALTH_FRESHNESS_MILLIS

            // Priority order: SUSPENDED, STARTING, STOPPED, DEGRADED, OPERATIONAL,
            // SERVICE_ALIVE. UNKNOWN is the store's pre-publication state (no evidence yet).
            val health = finalState ?: when {
                !screenInteractive -> HealthState.SUSPENDED
                startup -> HealthState.STARTING
                !heartbeatFresh -> HealthState.STOPPED
                !usageAccessGranted -> HealthState.DEGRADED
                runSuccessFresh -> HealthState.OPERATIONAL
                evidence.queryOutcome == QueryOutcome.EXCEPTION -> HealthState.DEGRADED
                else -> HealthState.SERVICE_ALIVE
            }

            val currentPackage = evidence.currentForegroundPackage
            val currentMonitored =
                if (currentPackage != null) monitoredPackages.contains(currentPackage) else null
            val tracking = when {
                !monitoringActive -> TrackingState.COUNTER_DISABLED
                finalState != null -> TrackingState.UNKNOWN
                currentPackage == null -> TrackingState.UNKNOWN
                currentMonitored == true -> TrackingState.MONITORED_APP_ACTIVE
                else -> TrackingState.NO_MONITORED_APP_ACTIVE
            }

            MonitoringStatusStore.publish(
                MonitoringStatus(
                    processInstanceId = evidence.processInstanceId,
                    health = health,
                    tracking = tracking,
                    lastHeartbeatRealtimeMs =
                        PhoenixHealthMonitor.getLastHeartbeatRealtime().takeIf { it > 0L },
                    lastEvaluationRealtimeMs = evidence.lastEvaluationRealtimeMs,
                    lastSuccessfulEvaluationRealtimeMs = evidence.lastSuccessfulEvaluationRealtimeMs,
                    lastQueryCompletedRealtimeMs = evidence.lastQueryCompletedRealtimeMs,
                    lastQueryOutcome = evidence.queryOutcome,
                    evaluationException = evidence.evaluationException,
                    currentForegroundPackage = currentPackage,
                    currentForegroundMonitored = currentMonitored,
                    transitionGeneration = evidence.transitionGeneration,
                    transitionPackage = evidence.transitionPackage,
                    transitionEventTimeMs = evidence.transitionEventTimeMs,
                    transitionObservedRealtimeMs = evidence.transitionObservedRealtimeMs,
                    transitionOrderingAmbiguous = evidence.transitionOrderingAmbiguous,
                    transitionIdentity = evidence.transitionIdentity,
                    screenInteractive = screenInteractive,
                    usageAccessGranted = usageAccessGranted,
                    lastError = evidence.exceptionMessage
                )
            )
        } catch (e: Exception) {
            // Status publication is informational; never let it affect monitoring.
            Log.w(TAG, "Monitoring status publication failed", e)
        }
    }

    override fun onCreate() {
        super.onCreate()

        appPreferences = AppPreferences.getInstance(this)
        foregroundDetector = ForegroundAppDetector(this)
        overlayController = OverlayController(this)
        historyRepository = HistoryRepository.getInstance(this)

        val savedDate = appPreferences.getLastActiveDate()
        val today = AppPreferences.getTodayDateString()

        // M1/M3: restore persisted values (prefs reads happen outside stateLock), then
        // install the tracking date + counters + map as one consistent snapshot.
        val restoredWithTimerSec = appPreferences.getTodayWithTimerSeconds()
        val restoredWithoutTimerSec = appPreferences.getTodayWithoutTimerSeconds()
        val restoredAppUsage = appPreferences.getTodayAppUsageMap()
        synchronized(stateLock) {
            currentTrackingDate = today
            todayWithTimerSec = restoredWithTimerSec
            todayWithoutTimerSec = restoredWithoutTimerSec
            todayAppUsageMap.clear()
            todayAppUsageMap.putAll(restoredAppUsage)
        }

        if (savedDate.isNotEmpty() && savedDate != today) {
            // New day on service startup: finalize previous day's record.
            // H1-F1: capture the authoritative stopped-time value once and use that exact
            // value for finalization and for the atomic counter/date rollover below.
            val recordedStoppedSec = appPreferences.getTodayMonitoringStoppedSeconds()
            // M1/M3: one consistent snapshot; the database work happens outside stateLock.
            val snapshot = snapshotLocked()
            historyRepository.finalizeDay(
                date = savedDate,
                withTimerSeconds = snapshot.withTimerSec,
                withoutTimerSeconds = snapshot.withoutTimerSec,
                stoppedSeconds = recordedStoppedSec,
                appUsageMap = snapshot.appUsage,
                monitoredPackages = appPreferences.getMonitoredPackages()
            )
            resetCountersLocked(today)

            TimerEngine.initialize(0L, 0L, today)
            appPreferences.advanceDayAndConsumeStoppedTime(0L, 0L, today, recordedStoppedSec)
            appPreferences.saveTodayAppUsageMap(emptyMap())
        } else {
            val snapshot = snapshotLocked()
            TimerEngine.initialize(snapshot.withTimerSec, snapshot.withoutTimerSec, today)
        }

        createNotificationChannel()
        startForegroundServiceNotification()
        if (foregroundPromotionFailed) {
            // F3: the service is already being stopped; do not register receivers, start the
            // orchestrator loop, or leave a partially initialised instance behind.
            return
        }
        registerScreenReceiver()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (powerManager.isInteractive) {
            startOrchestratorLoop()
        }
        // Stage 3: STARTING (or SUSPENDED when the screen is off) until the first completed
        // evaluation publishes the real state.
        publishMonitoringStatus(startup = true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // F3: an instance whose foreground promotion failed must never report a sticky start.
        // onCreate() runs before onStartCommand(), so the flag is already set when this start
        // request is delivered. START_NOT_STICKY only applies to this request: it does not
        // prevent legitimate future starts (user action, watchdog, boot) from creating a new
        // instance, and it is not a guarantee against every platform restart.
        if (foregroundPromotionFailed) {
            Log.e(TAG, "Foreground promotion failed; returning START_NOT_STICKY for this start request")
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                PhoenixAlarmManager.cancel(this)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE_CONFIG -> {
                // Config updated, will be picked up on next tick
            }
        }
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (powerManager.isInteractive && (orchestratorJob == null || orchestratorJob?.isActive == false)) {
            startOrchestratorLoop()
        }
        return START_STICKY
    }

    private fun startOrchestratorLoop() {
        // Correction B: the run generation and its lifecycle boundary are established atomically
        // with publication. An obsolete run therefore can never publish after this point.
        val runGeneration: Long
        val runBoundaryRealtimeMs: Long
        synchronized(publicationLock) {
            orchestratorRunGeneration += 1
            runGeneration = orchestratorRunGeneration
            runBoundaryRealtimeMs = SystemClock.elapsedRealtime()
        }
        orchestratorJob?.cancel()
        orchestratorJob = serviceScope.launch {
            // M2: single-owner loop body. A cancelled predecessor may still be finishing
            // its non-suspending tail; this coroutine suspends here (never blocking a
            // lifecycle callback) until that tail ends and the mutex is released.
            loopMutex.withLock {
                // Superseded while waiting for the mutex: exit without touching any state.
                if (runGeneration != orchestratorRunGeneration) return@launch
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

                // C1: the authoritative counter advances by measured monotonic elapsed
                // time, not by a fixed "+1" per iteration. Both the timestamp and the
                // fractional remainder are (re)initialised here, at every loop start and
                // restart, so a restart can never credit the gap since the previous job
                // stopped (e.g. a screen-off session or a sticky service restart).
                // M2: initialised AFTER the mutex is acquired so the wait is never credited.
                var lastIterationRealtimeMs = SystemClock.elapsedRealtime()
                val elapsedCredit = ElapsedCredit()

                while (isActive) {
                    val nowRealtimeMs = SystemClock.elapsedRealtime()
                    val deltaMs = nowRealtimeMs - lastIterationRealtimeMs
                    lastIterationRealtimeMs = nowRealtimeMs

                    if (!powerManager.isInteractive) {
                        // Screen off: this interval must not count, and no carried
                        // remainder may survive into the next tracked stretch.
                        elapsedCredit.reset()
                        overlayController.hide()
                        // Correction B: only the current run may publish this defensive state.
                        synchronized(publicationLock) {
                            if (!isDestroying && runGeneration == orchestratorRunGeneration) {
                                publishMonitoringStatusLocked(
                                    startup = false,
                                    finalState = null,
                                    currentRunSuccessRealtimeMs = null
                                )
                            }
                        }
                        delay(1000)
                        continue
                    }

                    val today = AppPreferences.getTodayDateString()
                    // Stage 3.2.3A: the tracking date is read under stateLock (no unsynchronized
                    // field access), so the rollover decision and the captured snapshot agree.
                    val trackingDateBeforeTick = synchronized(stateLock) { currentTrackingDate }
                    val isNewDay = TimerEngine.checkAndApplyMidnightRollover(today) ||
                            (trackingDateBeforeTick.isNotEmpty() && trackingDateBeforeTick != today)

                    if (isNewDay) {
                        // M2: a failed day finalization must never be retried every second
                        // (a 1 Hz database retry would spin while the day stays stale).
                        // Log it and stop this loop; an existing restart trigger
                        // (screen-on, onStartCommand, Phoenix watchdog) recovers it later.
                        try {
                            // Stage 3.2.3A: one stateLock capture provides the date and the
                            // counters together, so previousDate can never be paired with
                            // counters that belong to a different tracking date.
                            val snapshot = snapshotLocked()
                            val previousDate = if (snapshot.trackingDate.isNotEmpty()) snapshot.trackingDate else appPreferences.getLastActiveDate()
                            // H1-F1: capture the authoritative stopped-time value once, use
                            // that exact value for finalization, and consume it atomically
                            // with the counter/date rollover below. Database work stays
                            // outside every lock.
                            val recordedStoppedSec = appPreferences.getTodayMonitoringStoppedSeconds()
                            if (previousDate.isNotEmpty() && previousDate != today) {
                                historyRepository.finalizeDay(
                                    date = previousDate,
                                    withTimerSeconds = snapshot.withTimerSec,
                                    withoutTimerSeconds = snapshot.withoutTimerSec,
                                    stoppedSeconds = recordedStoppedSec,
                                    appUsageMap = snapshot.appUsage,
                                    monitoredPackages = appPreferences.getMonitoredPackages()
                                )
                            }
                            // Stage 3.2.3A: counter reset and tracking-date assignment are one
                            // atomic step under stateLock.
                            resetCountersLocked(today)

                            // C1: never carry a sub-second remainder across the day boundary.
                            elapsedCredit.reset()

                            TimerEngine.resetToday(today)
                            appPreferences.advanceDayAndConsumeStoppedTime(0L, 0L, today, recordedStoppedSec)
                            appPreferences.saveTodayAppUsageMap(emptyMap())
                        } catch (e: CancellationException) {
                            // Cancellation must always propagate (screen-off / service stop).
                            throw e
                        } catch (e: Exception) {
                            Log.e(TAG, "Day rollover/finalization failed; stopping orchestrator loop (no automatic 1 Hz retry) so an existing restart trigger can recover it", e)
                            return@launch
                        }
                        // Widget 1: the new day is established (counters reset, date advanced).
                        refreshScreenTimeWidget()
                    }

                    val monitoredPackages = appPreferences.getMonitoredPackages()
                    // Correction C: baseline captured before this tick's own detector call.
                    val successRealtimeBeforeTick =
                        foregroundDetector.evidenceSnapshot().lastSuccessfulEvaluationRealtimeMs
                    val isMonitoredActive = foregroundDetector.isMonitoredAppActive(monitoredPackages)
                    // Widget 1: refresh only on a genuine tracking start/pause/resume transition.
                    if (lastTrackingActive != isMonitoredActive) {
                        lastTrackingActive = isMonitoredActive
                        refreshScreenTimeWidget()
                    }
                    val showOnScreenTime = appPreferences.isShowOnScreenTimeEnabled()

                    // C1: credit the *measured* interval, carrying the sub-second
                    // remainder forward. When no monitored app is in the foreground the
                    // interval is discarded (and the remainder reset) instead of credited.
                    val creditedSeconds = elapsedCredit.onInterval(deltaMs, credit = isMonitoredActive)

                    if (isMonitoredActive) {
                        // M1/M3: the UsageStats/Binder query runs BEFORE stateLock; the
                        // critical section below is strictly in-memory.
                        val detectedPkg = if (creditedSeconds > 0L) {
                            foregroundDetector.detectForegroundPackage()
                        } else {
                            null
                        }
                        val (withTimerNow, withoutTimerNow) = synchronized(stateLock) {
                            if (creditedSeconds > 0L) {
                                if (showOnScreenTime) {
                                    todayWithTimerSec += creditedSeconds
                                } else {
                                    todayWithoutTimerSec += creditedSeconds
                                }
                                if (detectedPkg != null && monitoredPackages.contains(detectedPkg)) {
                                    // Same credited seconds, so the per-app totals and the
                                    // daily total stay on one scale.
                                    todayAppUsageMap[detectedPkg] =
                                        (todayAppUsageMap[detectedPkg] ?: 0L) + creditedSeconds
                                }
                            }
                            todayWithTimerSec to todayWithoutTimerSec
                        }
                        TimerEngine.updateCounters(withTimerNow, withoutTimerNow)
                        TimerEngine.resume()
                        if (showOnScreenTime) {
                            overlayController.show()
                            overlayController.updateTime(TimerEngine.getElapsedTodaySeconds())
                        } else {
                            overlayController.hide()
                        }
                    } else {
                        TimerEngine.pause()
                        overlayController.hide()
                    }

                    TimerEngine.tick()
                    val elapsedSeconds = TimerEngine.getElapsedTodaySeconds()

                    // Widget 1: refresh when the displayed minute changes (never every second).
                    val displayedMinute = elapsedSeconds / 60L
                    if (displayedMinute != lastWidgetMinute) {
                        lastWidgetMinute = displayedMinute
                        refreshScreenTimeWidget()
                    }

                    // C1: persist on a real-time cadence. The previous trigger
                    // ("elapsedSeconds % 5 == 0") can now be stepped over because the
                    // counter may advance by more than one second per iteration, which
                    // could skip save cycles and delay persistence.
                    if (elapsedSeconds != lastSavedSeconds &&
                        nowRealtimeMs - lastSavedRealtimeMs >= SAVE_INTERVAL_MILLIS
                    ) {
                        lastSavedRealtimeMs = nowRealtimeMs
                        lastSavedSeconds = elapsedSeconds
                        try {
                            // M1/M3: one consistent snapshot; persistence runs outside stateLock.
                            val snapshot = snapshotLocked()
                            // H1-F1: ordinary flush -- never writes the stopped-time key.
                            appPreferences.saveTodayHistoryCounters(
                                snapshot.withTimerSec,
                                snapshot.withoutTimerSec,
                                today
                            )
                            appPreferences.saveTodayAppUsageMap(snapshot.appUsage)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // Recoverable: the counters are already updated in memory and
                            // the next flush cycle retries.
                            Log.e(TAG, "Periodic counter/map flush failed; retrying on the next flush cycle", e)
                        }
                    }

                    // Update notification text
                    try {
                        updateNotification(elapsedSeconds, isMonitoredActive)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Notification update failed; retrying on the next iteration", e)
                    }

                    // Correction C: prove this tick (this run) completed a successful evaluation
                    // after the run boundary; computed before taking the publication lock.
                    val currentRunSuccessRealtimeMs =
                        currentRunSuccessAfterTick(successRealtimeBeforeTick, runBoundaryRealtimeMs)

                    // Correction A/B: heartbeat + publication are serialised with the destruction
                    // boundary and the run generation; nothing is recorded after either changes.
                    synchronized(publicationLock) {
                        if (!isDestroying && runGeneration == orchestratorRunGeneration) {
                            PhoenixHealthMonitor.recordHeartbeat()
                            publishMonitoringStatusLocked(
                                startup = false,
                                finalState = null,
                                currentRunSuccessRealtimeMs = currentRunSuccessRealtimeMs
                            )
                        }
                    }

                    delay(1000)
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundServiceNotification() {
        val notification = buildNotification(TimerEngine.getElapsedTodaySeconds(), false)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                }
                startForeground(NOTIFICATION_ID, notification, fgsType)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (fallbackEx: Exception) {
                // F3: both promotion attempts failed. An unpromoted service violates the
                // startForegroundService contract and is eventually killed by the platform,
                // so record the failure and stop deliberately instead of leaving a zombie.
                // No retry loop: the next legitimate start (user action / watchdog / boot)
                // creates a new instance and tries again.
                foregroundPromotionFailed = true
                Log.e(TAG, "Foreground promotion failed (both attempts); stopping service", fallbackEx)
                if (e !== fallbackEx) {
                    Log.e(TAG, "Primary foreground promotion failure (retained cause)", e)
                }
                stopSelf()
            }
        }
    }

    private fun updateNotification(seconds: Long, isTracking: Boolean) {
        val notification = buildNotification(seconds, isTracking)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(seconds: Long, isTracking: Boolean): Notification {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        val timeString = String.format("%02d:%02d:%02d", hours, minutes, secs)

        val statusText = if (isTracking) "Monitoring active app • $timeString" else "Idle • $timeString"

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name) + " Active")
            .setContentText(statusText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun registerScreenReceiver() {
        if (!isScreenReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
            isScreenReceiverRegistered = true
        }
    }

    private fun unregisterScreenReceiver() {
        if (isScreenReceiverRegistered) {
            try {
                unregisterReceiver(screenReceiver)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isScreenReceiverRegistered = false
            }
        }
    }

    override fun onDestroy() {
        // Correction A: establish the destruction boundary atomically with publication. Any
        // publication already holding the lock finishes first; every later orchestrator heartbeat
        // and non-final publication is excluded, and the final publication is the last writer.
        synchronized(publicationLock) {
            isDestroying = true
        }
        if (::appPreferences.isInitialized && !appPreferences.isMonitoringActive()) {
            PhoenixAlarmManager.cancel(this)
        }
        PhoenixHealthMonitor.reset()
        unregisterScreenReceiver()
        orchestratorJob?.cancel()
        overlayController.destroy()
        TimerEngine.pause()
        val today = AppPreferences.getTodayDateString()
        // M-1: while a day rollover is in flight the orchestrator loop owns persistence
        // (it updates the tracking date only after resetting the counters). Do not race this
        // shutdown flush against it: the in-memory counters may still belong to the previous
        // day. A mismatched date can only make this skip, never write a wrong pair.
        // Stage 3.2.3A: date + counters come from one stateLock capture, and the flush runs
        // only when that date is valid and matches the current wall-clock date.
        val finalSnapshot = snapshotLocked()
        if (finalSnapshot.trackingDate.isNotEmpty() && finalSnapshot.trackingDate == today) {
            // H1-F1: ordinary flush -- never writes the stopped-time key.
            appPreferences.saveTodayHistoryCounters(
                finalSnapshot.withTimerSec,
                finalSnapshot.withoutTimerSec,
                finalSnapshot.trackingDate
            )
            appPreferences.saveTodayAppUsageMap(finalSnapshot.appUsage)
        }
        // Stage 3.1: final publication. It acquires the publication lock, so any in-flight
        // publication finishes first and this final STOPPED/SUSPENDED state is the last write.
        val screenInteractive = (getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive ?: true
        publishMonitoringStatus(
            finalState = if (screenInteractive) HealthState.STOPPED else HealthState.SUSPENDED
        )
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "TimerOverlayService"
        const val CHANNEL_ID = "timemirror_monitoring_channel"
        const val NOTIFICATION_ID = 1001
        const val PHOENIX_ALARM_DELAY_MILLIS = 15 * 60 * 1000L // 15 minutes

        /** Persist today's counters every ~5s of real time (C1: was "every 5 credited seconds"). */
        private const val SAVE_INTERVAL_MILLIS = 5000L

        /** Heartbeat/evaluation freshness window (matches the Phoenix watchdog threshold). */
        private const val HEALTH_FRESHNESS_MILLIS = 3000L

        const val ACTION_START_SERVICE = "com.mukundbhujbal.timemirror.action.START"
        const val ACTION_STOP_SERVICE = "com.mukundbhujbal.timemirror.action.STOP"
        const val ACTION_UPDATE_CONFIG = "com.mukundbhujbal.timemirror.action.UPDATE_CONFIG"

        fun startService(context: Context) {
            // Correction D: the companion-side partial STARTING hint was removed. It ran outside
            // publicationLock and could race with (or overwrite) a full or final snapshot. The
            // service remains the sole publisher: onCreate() publishes STARTING within the
            // service lifecycle.
            val intent = Intent(context, TimerOverlayService::class.java).apply {
                action = ACTION_START_SERVICE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            PhoenixAlarmManager.cancel(context)
            val intent = Intent(context, TimerOverlayService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            context.stopService(intent)
        }

        fun notifyConfigChanged(context: Context) {
            val intent = Intent(context, TimerOverlayService::class.java).apply {
                action = ACTION_UPDATE_CONFIG
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                // Ignore if service is not running
            }
        }
    }
}
