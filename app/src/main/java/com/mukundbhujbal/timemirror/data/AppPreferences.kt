package com.mukundbhujbal.timemirror.data

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class AppPreferences(context: Context) {

    private val appContext: Context = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _monitoredPackages = MutableStateFlow(getMonitoredPackages())
    val monitoredPackagesFlow: StateFlow<Set<String>> = _monitoredPackages.asStateFlow()

    private val _showOnScreenTime = MutableStateFlow(isShowOnScreenTimeEnabled())
    val showOnScreenTimeFlow: StateFlow<Boolean> = _showOnScreenTime.asStateFlow()

    private val _hideUntilTimestamp = MutableStateFlow(getHideTimerUntilTimestamp())
    val hideUntilTimestampFlow: StateFlow<Long> = _hideUntilTimestamp.asStateFlow()

    private val _isTimerHidden = MutableStateFlow(isTimerTemporarilyHidden())
    val isTimerHiddenFlow: StateFlow<Boolean> = _isTimerHidden.asStateFlow()

    private val _isMonitoringActive = MutableStateFlow(isMonitoringActive())
    val isMonitoringActiveFlow: StateFlow<Boolean> = _isMonitoringActive.asStateFlow()

    private val _userName = MutableStateFlow(getUserName())
    val userNameFlow: StateFlow<String> = _userName.asStateFlow()

    fun getMonitoredPackages(): Set<String> {
        return prefs.getStringSet(KEY_MONITORED_PACKAGES, emptySet()) ?: emptySet()
    }

    fun setMonitoredPackages(packages: Set<String>) {
        prefs.edit().putStringSet(KEY_MONITORED_PACKAGES, packages).apply()
        _monitoredPackages.value = packages
    }

    fun toggleAppMonitored(packageName: String, monitored: Boolean) {
        val current = getMonitoredPackages().toMutableSet()
        if (monitored) {
            current.add(packageName)
        } else {
            current.remove(packageName)
        }
        setMonitoredPackages(current)
    }

    /**
     * Temporary Hide On-Screen Timer APIs.
     * Default state is Visible (!isTimerTemporarilyHidden()).
     */
    fun getHideTimerUntilTimestamp(): Long {
        return prefs.getLong(KEY_HIDE_TIMER_UNTIL_TIMESTAMP, 0L)
    }

    fun isTimerTemporarilyHidden(): Boolean {
        val expiry = getHideTimerUntilTimestamp()
        if (expiry > 0L && expiry <= System.currentTimeMillis()) {
            resumeTimerNow()
            return false
        }
        return expiry > System.currentTimeMillis()
    }

    fun isShowOnScreenTimeEnabled(): Boolean {
        // Visible by default; hidden only when an unexpired temporary hide is active.
        return !isTimerTemporarilyHidden()
    }

    fun hideTimerForHours(hours: Int) {
        val clampedHours = hours.coerceIn(1, 6)
        val expiry = System.currentTimeMillis() + (clampedHours * 3600 * 1000L)
        prefs.edit().putLong(KEY_HIDE_TIMER_UNTIL_TIMESTAMP, expiry).apply()
        _hideUntilTimestamp.value = expiry
        _isTimerHidden.value = true
        _showOnScreenTime.value = false
    }

    fun resumeTimerNow() {
        prefs.edit().putLong(KEY_HIDE_TIMER_UNTIL_TIMESTAMP, 0L).apply()
        _hideUntilTimestamp.value = 0L
        _isTimerHidden.value = false
        _showOnScreenTime.value = true
    }

    fun getRemainingHideDurationSeconds(): Long {
        val expiry = getHideTimerUntilTimestamp()
        val now = System.currentTimeMillis()
        return ((expiry - now) / 1000L).coerceAtLeast(0L)
    }

    fun setShowOnScreenTimeEnabled(enabled: Boolean) {
        if (enabled) {
            resumeTimerNow()
        } else {
            hideTimerForHours(1)
        }
    }

    fun isMonitoringActive(): Boolean {
        return prefs.getBoolean(KEY_IS_MONITORING_ACTIVE, true)
    }

    fun setMonitoringActive(active: Boolean) {
        val previous = isMonitoringActive()
        prefs.edit().putBoolean(KEY_IS_MONITORING_ACTIVE, active).apply()
        _isMonitoringActive.value = active

        if (previous != active) {
            if (!active) {
                // Stopped master monitoring - record active uptime & wall-clock anchors
                setMonitoringStoppedAnchor(SystemClock.elapsedRealtime(), System.currentTimeMillis())
            } else {
                // Resumed master monitoring - accumulate stopped delta and clear anchors
                accumulateMonitoringStoppedTime()
                setMonitoringStoppedAnchor(0L, 0L)
            }
        }
    }

    /**
     * Authoritative today's Total Screen Time in seconds: With OST + Without OST.
     */
    fun getTodayTotalScreenTimeSeconds(): Long {
        return getTodayWithTimerSeconds() + getTodayWithoutTimerSeconds()
    }

    /**
     * Millisecond representation of today's Total Screen Time strictly derived from (With OST + Without OST).
     */
    fun getCumulativeTimeTodayMillis(): Long {
        return getTodayTotalScreenTimeSeconds() * 1000L
    }

    fun saveCumulativeTimeTodayMillis(millis: Long, date: String) {
        prefs.edit()
            .putString(KEY_LAST_ACTIVE_DATE, date)
            .apply()
    }

    fun getLastActiveDate(): String {
        return prefs.getString(KEY_LAST_ACTIVE_DATE, "") ?: ""
    }

    fun resetCumulativeTime(todayDate: String = getTodayDateString()) {
        saveTodayHistoryCounters(0L, 0L, 0L, todayDate)
        saveTodayAppUsageMap(emptyMap())
        setMonitoringStoppedAnchor(0L, 0L)
        resumeTimerNow()
    }

    // --- Daily History Data Layer Helpers ---

    fun ensureInstallDate(): String {
        var installDate = prefs.getString(KEY_INSTALL_DATE, null)
        if (installDate == null) {
            installDate = try {
                val pkgInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
                SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(pkgInfo.firstInstallTime))
            } catch (e: Exception) {
                getTodayDateString()
            }
            prefs.edit().putString(KEY_INSTALL_DATE, installDate).apply()
        }
        return installDate
    }

    fun getInstallDate(): String {
        return ensureInstallDate()
    }

    fun setInstallDate(date: String) {
        prefs.edit().putString(KEY_INSTALL_DATE, date).apply()
    }

    fun getOriginalInstallDate(): String? {
        return prefs.getString(KEY_ORIGINAL_INSTALL_DATE, null)
    }

    fun setOriginalInstallDate(date: String?) {
        if (date == null) {
            prefs.edit().remove(KEY_ORIGINAL_INSTALL_DATE).apply()
        } else {
            prefs.edit().putString(KEY_ORIGINAL_INSTALL_DATE, date).apply()
        }
    }

    fun getUserName(): String {
        return prefs.getString(KEY_USER_NAME, "") ?: ""
    }

    fun setUserName(name: String?) {
        val cleanName = name?.trim() ?: ""
        if (cleanName.isEmpty()) {
            prefs.edit().remove(KEY_USER_NAME).apply()
            _userName.value = ""
        } else {
            prefs.edit().putString(KEY_USER_NAME, cleanName).apply()
            _userName.value = cleanName
        }
    }

    fun getTodayWithTimerSeconds(): Long {
        return prefs.getLong(KEY_TODAY_WITH_TIMER_SEC, 0L)
    }

    fun getTodayWithoutTimerSeconds(): Long {
        return prefs.getLong(KEY_TODAY_WITHOUT_TIMER_SEC, 0L)
    }

    fun getTodayMonitoringStoppedSeconds(): Long {
        return prefs.getLong(KEY_TODAY_STOPPED_SEC, 0L)
    }

    fun saveTodayHistoryCounters(
        withTimerSec: Long,
        withoutTimerSec: Long,
        stoppedSec: Long,
        date: String
    ) {
        prefs.edit()
            .putLong(KEY_TODAY_WITH_TIMER_SEC, withTimerSec)
            .putLong(KEY_TODAY_WITHOUT_TIMER_SEC, withoutTimerSec)
            .putLong(KEY_TODAY_STOPPED_SEC, stoppedSec)
            .putString(KEY_LAST_ACTIVE_DATE, date)
            .apply()
    }

    fun getTodayAppUsageMap(): Map<String, Long> {
        val jsonStr = prefs.getString(KEY_TODAY_APP_USAGE_JSON, null) ?: return emptyMap()
        val map = mutableMapOf<String, Long>()
        try {
            val json = org.json.JSONObject(jsonStr)
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = json.optLong(key, 0L)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return map
    }

    fun saveTodayAppUsageMap(map: Map<String, Long>) {
        try {
            val json = org.json.JSONObject()
            for ((pkg, sec) in map) {
                json.put(pkg, sec)
            }
            prefs.edit().putString(KEY_TODAY_APP_USAGE_JSON, json.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getMonitoringStoppedRealtime(): Long {
        return prefs.getLong(KEY_MONITORING_STOPPED_REALTIME, 0L)
    }

    fun getMonitoringStoppedTimestamp(): Long {
        return prefs.getLong(KEY_MONITORING_STOPPED_TIMESTAMP, 0L)
    }

    fun setMonitoringStoppedAnchor(realtime: Long, wallClock: Long) {
        prefs.edit()
            .putLong(KEY_MONITORING_STOPPED_REALTIME, realtime)
            .putLong(KEY_MONITORING_STOPPED_TIMESTAMP, wallClock)
            .apply()
    }

    /**
     * Robustly accumulates Monitoring Stopped Time while device is running.
     * Excludes power-off / reboot downtime using SystemClock.elapsedRealtime().
     * Handles midnight splits across calendar days.
     */
    fun accumulateMonitoringStoppedTime(todayDate: String = getTodayDateString()) {
        val startRealtime = getMonitoringStoppedRealtime()
        val startWallClock = getMonitoringStoppedTimestamp()
        if (startRealtime <= 0L && startWallClock <= 0L) return

        val nowRealtime = SystemClock.elapsedRealtime()
        val nowWallClock = System.currentTimeMillis()
        val lastActiveDate = getLastActiveDate().ifEmpty { todayDate }

        // Compute actual running elapsed seconds (excluding device power-off downtime)
        val elapsedSec: Long = if (startRealtime > 0L && nowRealtime >= startRealtime) {
            // Normal operation within same device boot session
            (nowRealtime - startRealtime) / 1000L
        } else if (startRealtime > 0L && nowRealtime < startRealtime) {
            // Device rebooted while monitoring was OFF -> count only active uptime since reboot
            (nowRealtime / 1000L).coerceAtLeast(0L)
        } else if (startWallClock > 0L) {
            // Fallback bounded strictly by current uptime
            val wallElapsed = ((nowWallClock - startWallClock) / 1000L).coerceAtLeast(0L)
            wallElapsed.coerceAtMost(nowRealtime / 1000L)
        } else {
            0L
        }

        if (elapsedSec > 0L) {
            if (lastActiveDate == todayDate) {
                // Same calendar day
                val currentStopped = getTodayMonitoringStoppedSeconds()
                prefs.edit()
                    .putLong(KEY_TODAY_STOPPED_SEC, currentStopped + elapsedSec)
                    .putString(KEY_LAST_ACTIVE_DATE, todayDate)
                    .apply()
            } else {
                // Cross-midnight scenario while monitoring was OFF
                val (prevDayDelta, _) = calculateMidnightStoppedSplit(startWallClock, nowWallClock, elapsedSec)
                val currentStopped = getTodayMonitoringStoppedSeconds()
                prefs.edit()
                    .putLong(KEY_TODAY_STOPPED_SEC, currentStopped + prevDayDelta)
                    .putString(KEY_LAST_ACTIVE_DATE, lastActiveDate)
                    .apply()
            }
        }

        if (!isMonitoringActive()) {
            setMonitoringStoppedAnchor(nowRealtime, nowWallClock)
        } else {
            setMonitoringStoppedAnchor(0L, 0L)
        }
    }

    /**
     * Splits elapsed stopped seconds across the 00:00:00 midnight boundary.
     * Returns Pair(secondsBeforeMidnight, secondsAfterMidnight).
     */
    fun calculateMidnightStoppedSplit(
        startWallClock: Long,
        nowWallClock: Long,
        totalElapsedSec: Long
    ): Pair<Long, Long> {
        if (startWallClock <= 0L || totalElapsedSec <= 0L) return Pair(totalElapsedSec, 0L)
        try {
            val calendar = Calendar.getInstance().apply {
                timeInMillis = nowWallClock
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val midnightMs = calendar.timeInMillis
            if (startWallClock < midnightMs) {
                val beforeMidnightSec = ((midnightMs - startWallClock) / 1000L).coerceAtLeast(0L)
                val allocatedPrevDay = beforeMidnightSec.coerceAtMost(totalElapsedSec)
                val allocatedToday = (totalElapsedSec - allocatedPrevDay).coerceAtLeast(0L)
                return Pair(allocatedPrevDay, allocatedToday)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return Pair(totalElapsedSec, 0L)
    }

    companion object {
        private const val PREFS_NAME = "timemirror_prefs"
        private const val KEY_MONITORED_PACKAGES = "key_monitored_packages"
        private const val KEY_SHOW_ON_SCREEN_TIME = "key_show_on_screen_time"
        private const val KEY_HIDE_TIMER_UNTIL_TIMESTAMP = "key_hide_timer_until_timestamp"
        private const val KEY_LAST_ACTIVE_DATE = "key_last_active_date"
        private const val KEY_IS_MONITORING_ACTIVE = "key_is_monitoring_active"

        // History data layer keys
        private const val KEY_INSTALL_DATE = "key_install_date"
        private const val KEY_ORIGINAL_INSTALL_DATE = "key_original_install_date"
        private const val KEY_USER_NAME = "key_user_name"
        private const val KEY_TODAY_WITH_TIMER_SEC = "key_today_with_timer_sec"
        private const val KEY_TODAY_WITHOUT_TIMER_SEC = "key_today_without_timer_sec"
        private const val KEY_TODAY_STOPPED_SEC = "key_today_stopped_sec"
        private const val KEY_TODAY_APP_USAGE_JSON = "key_today_app_usage_json"
        private const val KEY_MONITORING_STOPPED_TIMESTAMP = "key_monitoring_stopped_timestamp"
        private const val KEY_MONITORING_STOPPED_REALTIME = "key_monitoring_stopped_realtime"

        fun getTodayDateString(): String {
            return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        }

        @Volatile
        private var instance: AppPreferences? = null

        fun getInstance(context: Context): AppPreferences {
            return instance ?: synchronized(this) {
                instance ?: AppPreferences(context).also { instance = it }
            }
        }
    }
}
