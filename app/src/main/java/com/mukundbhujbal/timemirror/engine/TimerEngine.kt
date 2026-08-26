package com.mukundbhujbal.timemirror.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Thread-safe timer engine and live state provider.
 * Authoritative Total Screen Time is strictly derived as:
 * Total Screen Time = withTimerSeconds + withoutTimerSeconds
 * Eliminates independent cumulative baseline offsets and handles daily midnight resets.
 */
object TimerEngine {

    private val lock = Any()

    @Volatile
    private var withTimerSeconds: Long = 0L

    @Volatile
    private var withoutTimerSeconds: Long = 0L

    @Volatile
    private var lastRecordedDate: String = ""

    private val _timerSecondsFlow = MutableStateFlow(0L)
    val timerSecondsFlow: StateFlow<Long> = _timerSecondsFlow.asStateFlow()

    private val _isRunningFlow = MutableStateFlow(false)
    val isRunningFlow: StateFlow<Boolean> = _isRunningFlow.asStateFlow()

    /**
     * Initializes the engine with authoritative history counters for today.
     */
    fun initialize(withTimerSec: Long, withoutTimerSec: Long, savedDate: String) {
        synchronized(lock) {
            withTimerSeconds = withTimerSec
            withoutTimerSeconds = withoutTimerSec
            lastRecordedDate = savedDate
            _isRunningFlow.value = false
            _timerSecondsFlow.value = withTimerSeconds + withoutTimerSeconds
        }
    }

    /**
     * Overloaded initialize for backward compatibility if called with legacy millis.
     */
    fun initialize(savedMillis: Long, savedDate: String) {
        initialize(withTimerSec = savedMillis / 1000L, withoutTimerSec = 0L, savedDate = savedDate)
    }

    /**
     * Updates the live with-timer and without-timer counters from the orchestrator.
     */
    fun updateCounters(withTimerSec: Long, withoutTimerSec: Long) {
        synchronized(lock) {
            withTimerSeconds = withTimerSec
            withoutTimerSeconds = withoutTimerSec
            _timerSecondsFlow.value = withTimerSeconds + withoutTimerSeconds
        }
    }

    fun isRunning(): Boolean = synchronized(lock) {
        _isRunningFlow.value
    }

    fun resume(): Long {
        synchronized(lock) {
            _isRunningFlow.value = true
            val totalSeconds = withTimerSeconds + withoutTimerSeconds
            _timerSecondsFlow.value = totalSeconds
            return totalSeconds * 1000L
        }
    }

    fun pause(): Long {
        synchronized(lock) {
            _isRunningFlow.value = false
            val totalSeconds = withTimerSeconds + withoutTimerSeconds
            _timerSecondsFlow.value = totalSeconds
            return totalSeconds * 1000L
        }
    }

    fun tick(): Long {
        synchronized(lock) {
            val totalSeconds = withTimerSeconds + withoutTimerSeconds
            _timerSecondsFlow.value = totalSeconds
            return totalSeconds * 1000L
        }
    }

    fun getElapsedTodayMillis(): Long = synchronized(lock) {
        (withTimerSeconds + withoutTimerSeconds) * 1000L
    }

    fun getElapsedTodaySeconds(): Long = synchronized(lock) {
        withTimerSeconds + withoutTimerSeconds
    }

    fun getWithTimerSeconds(): Long = synchronized(lock) {
        withTimerSeconds
    }

    fun getWithoutTimerSeconds(): Long = synchronized(lock) {
        withoutTimerSeconds
    }

    /**
     * Checks if calendar date rolled over past midnight (00:00:00).
     * If date changed, resets today's count to 0 and returns true.
     */
    fun checkAndApplyMidnightRollover(todayDate: String): Boolean {
        synchronized(lock) {
            if (lastRecordedDate.isNotEmpty() && lastRecordedDate != todayDate) {
                // Midnight rolled over!
                withTimerSeconds = 0L
                withoutTimerSeconds = 0L
                lastRecordedDate = todayDate
                _timerSecondsFlow.value = 0L
                return true
            }
            if (lastRecordedDate.isEmpty()) {
                lastRecordedDate = todayDate
            }
            return false
        }
    }

    fun resetToday(todayDate: String) {
        synchronized(lock) {
            withTimerSeconds = 0L
            withoutTimerSeconds = 0L
            lastRecordedDate = todayDate
            _timerSecondsFlow.value = 0L
        }
    }
}
