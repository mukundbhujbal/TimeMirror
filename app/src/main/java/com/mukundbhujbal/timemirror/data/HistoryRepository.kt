package com.mukundbhujbal.timemirror.data

import android.content.Context
import java.time.LocalDate

/**
 * Repository interface & implementation managing access to DailyHistoryRecords and daily finalization.
 * Implements 1-year rolling history retention.
 */
class HistoryRepository(context: Context) {

    private val dbHelper = HistoryDatabaseHelper.getInstance(context)
    private val appPreferences = AppPreferences.getInstance(context)

    /**
     * Gets the confirmed application install date.
     */
    fun getInstallDate(): String {
        return appPreferences.ensureInstallDate()
    }

    /**
     * Saves a finalized DailyHistoryRecord to the database.
     */
    fun saveDailyRecord(record: DailyHistoryRecord) {
        dbHelper.insertOrUpdate(record)
    }

    /**
     * Retrieves the daily record for a specific date if it exists.
     */
    fun getRecordForDate(date: String): DailyHistoryRecord? {
        return dbHelper.getRecord(date)
    }

    /**
     * Retrieves daily records in an inclusive date range [startDate, endDate].
     */
    fun getRecordsInRange(startDate: String, endDate: String): List<DailyHistoryRecord> {
        return dbHelper.getRecordsInRange(startDate, endDate)
    }

    /**
     * Retrieves all recorded daily records.
     */
    fun getAllHistoryRecords(): List<DailyHistoryRecord> {
        return dbHelper.getAllRecords()
    }

    /**
     * Enforces the 1-year rolling retention policy relative to a reference date.
     * Records strictly older than 1 year before referenceDate are pruned from SQLite.
     */
    fun enforceRollingRetention(referenceDate: String) {
        try {
            val cutoffDate = LocalDate.parse(referenceDate).minusYears(1).toString()
            dbHelper.deleteRecordsOlderThan(cutoffDate)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Finalizes a completed calendar day into the history database:
     * 1. Filters and sorts appUsageMap to determine Top 1 to 3 monitored apps.
     * 2. Inserts/updates the DailyHistoryRecord into SQLite.
     * 3. Enforces 1-year rolling retention: removes records strictly older than 1 year.
     * 4. Ensures no historical record is saved prior to the install date.
     */
    fun finalizeDay(
        date: String,
        withTimerSeconds: Long,
        withoutTimerSeconds: Long,
        stoppedSeconds: Long,
        appUsageMap: Map<String, Long>,
        monitoredPackages: Set<String>
    ): DailyHistoryRecord? {
        val installDate = getInstallDate()
        if (date < installDate) {
            // Do not store records prior to installation
            return null
        }

        // Top 1 to 3 monitored apps with usage > 0
        val topApps = appUsageMap.entries
            .filter { monitoredPackages.contains(it.key) && it.value > 0L }
            .sortedByDescending { it.value }
            .take(3)
            .map { AppUsageStat(packageName = it.key, usageSeconds = it.value) }

        val record = DailyHistoryRecord(
            date = date,
            withOnScreenTimerSeconds = withTimerSeconds,
            withoutOnScreenTimerSeconds = withoutTimerSeconds,
            monitoringStoppedSeconds = stoppedSeconds,
            topApps = topApps
        )

        // Save new daily record first
        saveDailyRecord(record)

        // Then enforce 1-year rolling retention
        enforceRollingRetention(date)

        return record
    }

    companion object {
        @Volatile
        private var instance: HistoryRepository? = null

        fun getInstance(context: Context): HistoryRepository {
            return instance ?: synchronized(this) {
                instance ?: HistoryRepository(context).also { instance = it }
            }
        }
    }
}
