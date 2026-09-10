package com.mukundbhujbal.timemirror.util

import android.content.Context
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Temporary, passive forensic diagnostic logger for TimeAware.
 *
 * CRITICAL ARCHITECTURAL CONSTRAINTS:
 * - This logger is purely passive: it never restarts services, never launches activities,
 *   never schedules background work, and never acquires wakelocks.
 * - Thread-safe and non-blocking: all disk I/O operations are offloaded to [Dispatchers.IO].
 * - Fault-tolerant: any exception encountered during diagnostic logging is caught and swallowed;
 *   diagnostic failure must NEVER crash or interrupt TimeAware execution.
 * - Manages two files in context.filesDir:
 *     1) "forensic_last_state.txt" - Atomic single-record snapshot of the last known state.
 *     2) "forensic_events.log"     - Bounded ring-buffer event timeline (retains newest ~200 events).
 */
object ForensicLogger {

    const val FILE_LAST_STATE = "forensic_last_state.txt"
    const val FILE_EVENTS_LOG = "forensic_events.log"
    private const val FILE_TEMP_STATE = "forensic_last_state.tmp"
    private const val FILE_TEMP_EVENTS = "forensic_events.tmp"

    private const val MAX_EVENT_LINES = 200
    private const val PRUNE_THRESHOLD = 250

    @Volatile
    private var isInitialized = false

    @Volatile
    private var filesDir: File? = null

    private val loggerScope =
        CoroutineScope(Dispatchers.IO.limitedParallelism(1) + SupervisorJob())
    private val fileLock = Any()

    @Volatile
    private var estimatedLineCount: Int = 0

    /**
     * Initializes the logger with the application context.
     * Safe to call multiple times; idempotent.
     */
    fun initialize(context: Context) {
        if (isInitialized) return
        try {
            val appCtx = context.applicationContext
            filesDir = appCtx.filesDir
            isInitialized = true

            // Recalculate existing line count on background dispatcher
            loggerScope.launch {
                try {
                    synchronized(fileLock) {
                        val eventsFile = getEventsFile()
                        if (eventsFile != null && eventsFile.exists()) {
                            val lines = eventsFile.readLines()
                            estimatedLineCount = lines.size
                            if (estimatedLineCount > PRUNE_THRESHOLD) {
                                pruneEventsFileInternal(eventsFile, lines)
                            }
                        } else {
                            estimatedLineCount = 0
                        }
                    }
                } catch (t: Throwable) {
                    // Fail silently - diagnostic failure must never interrupt TimeAware
                }
            }
        } catch (t: Throwable) {
            // Fail silently
        }
    }

    /**
     * Logs a periodic heartbeat indicating active liveness.
     * Called by external orchestration (no internal scheduler/timer is used).
     */
    fun logHeartbeat(
        screenState: String = "ON",
        serviceState: String = "RUN",
        orchestratorState: String = "ACTIVE",
        foregroundPackage: String? = null,
        isMonitored: Boolean? = null,
        timerSeconds: Long? = null,
        overlayVisible: Boolean? = null
    ) {
        logEvent(
            eventName = "HEARTBEAT",
            screenState = screenState,
            serviceState = serviceState,
            orchestratorState = orchestratorState,
            foregroundPackage = foregroundPackage,
            isMonitored = isMonitored,
            timerSeconds = timerSeconds,
            overlayVisible = overlayVisible,
            errorInfo = null
        )
    }

    /**
     * Logs a discrete forensic event and updates the last known state.
     */
    fun logEvent(
        eventName: String,
        screenState: String = "UNKNOWN",
        serviceState: String = "RUN",
        orchestratorState: String = "UNKNOWN",
        foregroundPackage: String? = null,
        isMonitored: Boolean? = null,
        timerSeconds: Long? = null,
        overlayVisible: Boolean? = null,
        errorInfo: String? = null
    ) {
        try {
            val nowWall = System.currentTimeMillis()
            val nowElapsedRealtime = SystemClock.elapsedRealtime()
            val pid = Process.myPid()

            val record = formatRecord(
                wallTimestamp = nowWall,
                elapsedRealtime = nowElapsedRealtime,
                pid = pid,
                eventName = eventName,
                screenState = screenState,
                serviceState = serviceState,
                orchestratorState = orchestratorState,
                foregroundPackage = foregroundPackage ?: "NONE",
                isMonitored = when (isMonitored) {
                    true -> "1"
                    false -> "0"
                    null -> "N/A"
                },
                timerState = timerSeconds?.let { "${it}s" } ?: "N/A",
                overlayState = when (overlayVisible) {
                    true -> "VIS"
                    false -> "HID"
                    null -> "N/A"
                },
                errorInfo = errorInfo ?: "NONE"
            )

            dispatchWrite(record)
        } catch (t: Throwable) {
            // Forensic logger must never propagate an exception to the caller.
        }
    }

    /**
     * Logs an exception with class and message for failure boundary analysis.
     */
    fun logException(
        eventName: String,
        throwable: Throwable,
        screenState: String = "UNKNOWN",
        serviceState: String = "RUN",
        orchestratorState: String = "EXCEPTION",
        foregroundPackage: String? = null
    ) {
        val err = "${throwable.javaClass.simpleName}:${throwable.message ?: "no_message"}"
        logEvent(
            eventName = eventName,
            screenState = screenState,
            serviceState = serviceState,
            orchestratorState = orchestratorState,
            foregroundPackage = foregroundPackage,
            isMonitored = null,
            timerSeconds = null,
            overlayVisible = null,
            errorInfo = err
        )
    }

    /**
     * Reads the last known state string asynchronously.
     */
    suspend fun readLastState(): String? = withContext(Dispatchers.IO) {
        readLastStateSync()
    }

    /**
     * Reads recent timeline events asynchronously.
     */
    suspend fun readRecentEvents(): List<String> = withContext(Dispatchers.IO) {
        readRecentEventsSync()
    }

    /**
     * Synchronously reads the last known state string.
     */
    fun readLastStateSync(): String? {
        return try {
            synchronized(fileLock) {
                val stateFile = getLastStateFile()
                if (stateFile != null && stateFile.exists()) {
                    stateFile.readText()
                } else {
                    null
                }
            }
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Synchronously reads recent events list.
     */
    fun readRecentEventsSync(): List<String> {
        return try {
            synchronized(fileLock) {
                val eventsFile = getEventsFile()
                if (eventsFile != null && eventsFile.exists()) {
                    eventsFile.readLines()
                } else {
                    emptyList()
                }
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    private fun dispatchWrite(record: String) {
        loggerScope.launch {
            try {
                synchronized(fileLock) {
                    // 1. Atomic overwrite of last known state
                    writeLastStateInternal(record)

                    // 2. Append to event timeline
                    appendEventInternal(record)
                }
            } catch (t: Throwable) {
                // Fail silently - diagnostic failure must never crash TimeAware
            }
        }
    }

    private fun writeLastStateInternal(record: String) {
        val dir = filesDir ?: return
        try {
            val targetFile = File(dir, FILE_LAST_STATE)
            val tempFile = File(dir, FILE_TEMP_STATE)

            FileOutputStream(tempFile, false).use { fos ->
                fos.write(record.toByteArray(Charsets.UTF_8))
                fos.flush()
                fos.fd.sync()
            }

            if (!tempFile.renameTo(targetFile)) {
                // If rename fails, fallback to direct overwrite
                FileOutputStream(targetFile, false).use { fos ->
                    fos.write(record.toByteArray(Charsets.UTF_8))
                    fos.flush()
                    fos.fd.sync()
                }
                tempFile.delete()
            }
        } catch (t: Throwable) {
            // Fail silently
        }
    }

    private fun appendEventInternal(record: String) {
        val dir = filesDir ?: return
        try {
            val eventsFile = File(dir, FILE_EVENTS_LOG)
            val lineBytes = (record + "\n").toByteArray(Charsets.UTF_8)

            FileOutputStream(eventsFile, true).use { fos ->
                fos.write(lineBytes)
                fos.flush()
                fos.fd.sync()
            }

            estimatedLineCount++

            if (estimatedLineCount >= PRUNE_THRESHOLD) {
                val lines = eventsFile.readLines()
                pruneEventsFileInternal(eventsFile, lines)
            }
        } catch (t: Throwable) {
            // Fail silently
        }
    }

    private fun pruneEventsFileInternal(eventsFile: File, existingLines: List<String>) {
        val dir = filesDir ?: return
        try {
            val retainedLines = if (existingLines.size > MAX_EVENT_LINES) {
                existingLines.takeLast(MAX_EVENT_LINES)
            } else {
                existingLines
            }

            val tempFile = File(dir, FILE_TEMP_EVENTS)
            FileOutputStream(tempFile, false).use { fos ->
                for (line in retainedLines) {
                    fos.write((line + "\n").toByteArray(Charsets.UTF_8))
                }
                fos.flush()
                fos.fd.sync()
            }

            if (!tempFile.renameTo(eventsFile)) {
                FileOutputStream(eventsFile, false).use { fos ->
                    for (line in retainedLines) {
                        fos.write((line + "\n").toByteArray(Charsets.UTF_8))
                    }
                    fos.flush()
                    fos.fd.sync()
                }
                tempFile.delete()
            }

            estimatedLineCount = retainedLines.size
        } catch (t: Throwable) {
            // Fail silently
        }
    }

    private fun formatRecord(
        wallTimestamp: Long,
        elapsedRealtime: Long,
        pid: Int,
        eventName: String,
        screenState: String,
        serviceState: String,
        orchestratorState: String,
        foregroundPackage: String,
        isMonitored: String,
        timerState: String,
        overlayState: String,
        errorInfo: String
    ): String {
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(wallTimestamp))
        return "$dateStr|RT=$elapsedRealtime|PID=$pid|EVENT=$eventName|SCR=$screenState|SVC=$serviceState|ORCH=$orchestratorState|PKG=$foregroundPackage|MON=$isMonitored|TMR=$timerState|OVL=$overlayState|ERR=$errorInfo"
    }

    private fun getLastStateFile(): File? {
        val dir = filesDir ?: return null
        return File(dir, FILE_LAST_STATE)
    }

    private fun getEventsFile(): File? {
        val dir = filesDir ?: return null
        return File(dir, FILE_EVENTS_LOG)
    }
}
