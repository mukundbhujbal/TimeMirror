package com.mukundbhujbal.timemirror.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Native Android SQLiteOpenHelper providing lightweight persistent storage for DailyHistoryRecords.
 * Indexing on date ensures <1ms query performance for single-day and date-range queries.
 */
class HistoryDatabaseHelper(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_DAILY_HISTORY (
                $COL_DATE TEXT PRIMARY KEY,
                $COL_WITH_TIMER_SEC INTEGER NOT NULL DEFAULT 0,
                $COL_WITHOUT_TIMER_SEC INTEGER NOT NULL DEFAULT 0,
                $COL_STOPPED_SEC INTEGER NOT NULL DEFAULT 0,
                $COL_TOP_APP_1_PKG TEXT,
                $COL_TOP_APP_1_SEC INTEGER DEFAULT 0,
                $COL_TOP_APP_2_PKG TEXT,
                $COL_TOP_APP_2_SEC INTEGER DEFAULT 0,
                $COL_TOP_APP_3_PKG TEXT,
                $COL_TOP_APP_3_SEC INTEGER DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_history_date ON $TABLE_DAILY_HISTORY($COL_DATE)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Future schema migrations will be handled here
    }

    /**
     * Inserts or replaces a daily history record.
     */
    fun insertOrUpdate(record: DailyHistoryRecord) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_DATE, record.date)
            put(COL_WITH_TIMER_SEC, record.withOnScreenTimerSeconds)
            put(COL_WITHOUT_TIMER_SEC, record.withoutOnScreenTimerSeconds)
            put(COL_STOPPED_SEC, record.monitoringStoppedSeconds)

            val app1 = record.topApps.getOrNull(0)
            put(COL_TOP_APP_1_PKG, app1?.packageName)
            put(COL_TOP_APP_1_SEC, app1?.usageSeconds ?: 0L)

            val app2 = record.topApps.getOrNull(1)
            put(COL_TOP_APP_2_PKG, app2?.packageName)
            put(COL_TOP_APP_2_SEC, app2?.usageSeconds ?: 0L)

            val app3 = record.topApps.getOrNull(2)
            put(COL_TOP_APP_3_PKG, app3?.packageName)
            put(COL_TOP_APP_3_SEC, app3?.usageSeconds ?: 0L)
        }
        db.insertWithOnConflict(
            TABLE_DAILY_HISTORY,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    /**
     * Retrieves the daily record for a specific date (e.g. "2026-08-16").
     */
    fun getRecord(date: String): DailyHistoryRecord? {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_DAILY_HISTORY,
            null,
            "$COL_DATE = ?",
            arrayOf(date),
            null,
            null,
            null
        )
        return cursor.use {
            if (it.moveToFirst()) parseCursor(it) else null
        }
    }

    /**
     * Retrieves daily records within an inclusive date range [startDate, endDate] sorted ascending by date.
     */
    fun getRecordsInRange(startDate: String, endDate: String): List<DailyHistoryRecord> {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_DAILY_HISTORY,
            null,
            "$COL_DATE >= ? AND $COL_DATE <= ?",
            arrayOf(startDate, endDate),
            null,
            null,
            "$COL_DATE ASC"
        )
        val list = mutableListOf<DailyHistoryRecord>()
        cursor.use {
            while (it.moveToNext()) {
                list.add(parseCursor(it))
            }
        }
        return list
    }

    /**
     * Retrieves all recorded daily history sorted ascending by date.
     */
    fun getAllRecords(): List<DailyHistoryRecord> {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_DAILY_HISTORY,
            null,
            null,
            null,
            null,
            null,
            "$COL_DATE ASC"
        )
        val list = mutableListOf<DailyHistoryRecord>()
        cursor.use {
            while (it.moveToNext()) {
                list.add(parseCursor(it))
            }
        }
        return list
    }

    /**
     * Deletes all records strictly older than the specified cutoffDate (e.g. "2025-08-16").
     * Returns the number of deleted records.
     */
    fun deleteRecordsOlderThan(cutoffDate: String): Int {
        val db = writableDatabase
        return db.delete(
            TABLE_DAILY_HISTORY,
            "$COL_DATE < ?",
            arrayOf(cutoffDate)
        )
    }

    private fun parseCursor(cursor: Cursor): DailyHistoryRecord {
        val date = cursor.getString(cursor.getColumnIndexOrThrow(COL_DATE))
        val withTimer = cursor.getLong(cursor.getColumnIndexOrThrow(COL_WITH_TIMER_SEC))
        val withoutTimer = cursor.getLong(cursor.getColumnIndexOrThrow(COL_WITHOUT_TIMER_SEC))
        val stopped = cursor.getLong(cursor.getColumnIndexOrThrow(COL_STOPPED_SEC))

        val topApps = mutableListOf<AppUsageStat>()

        val pkg1 = cursor.getString(cursor.getColumnIndexOrThrow(COL_TOP_APP_1_PKG))
        val sec1 = cursor.getLong(cursor.getColumnIndexOrThrow(COL_TOP_APP_1_SEC))
        if (!pkg1.isNullOrEmpty() && sec1 > 0L) {
            topApps.add(AppUsageStat(pkg1, sec1))
        }

        val pkg2 = cursor.getString(cursor.getColumnIndexOrThrow(COL_TOP_APP_2_PKG))
        val sec2 = cursor.getLong(cursor.getColumnIndexOrThrow(COL_TOP_APP_2_SEC))
        if (!pkg2.isNullOrEmpty() && sec2 > 0L) {
            topApps.add(AppUsageStat(pkg2, sec2))
        }

        val pkg3 = cursor.getString(cursor.getColumnIndexOrThrow(COL_TOP_APP_3_PKG))
        val sec3 = cursor.getLong(cursor.getColumnIndexOrThrow(COL_TOP_APP_3_SEC))
        if (!pkg3.isNullOrEmpty() && sec3 > 0L) {
            topApps.add(AppUsageStat(pkg3, sec3))
        }

        return DailyHistoryRecord(
            date = date,
            withOnScreenTimerSeconds = withTimer,
            withoutOnScreenTimerSeconds = withoutTimer,
            monitoringStoppedSeconds = stopped,
            topApps = topApps
        )
    }

    companion object {
        private const val DATABASE_NAME = "timeaware_history.db"
        private const val DATABASE_VERSION = 1

        private const val TABLE_DAILY_HISTORY = "daily_history"
        private const val COL_DATE = "date"
        private const val COL_WITH_TIMER_SEC = "with_timer_seconds"
        private const val COL_WITHOUT_TIMER_SEC = "without_timer_seconds"
        private const val COL_STOPPED_SEC = "monitoring_stopped_seconds"
        private const val COL_TOP_APP_1_PKG = "top_app_1_pkg"
        private const val COL_TOP_APP_1_SEC = "top_app_1_seconds"
        private const val COL_TOP_APP_2_PKG = "top_app_2_pkg"
        private const val COL_TOP_APP_2_SEC = "top_app_2_seconds"
        private const val COL_TOP_APP_3_PKG = "top_app_3_pkg"
        private const val COL_TOP_APP_3_SEC = "top_app_3_seconds"

        @Volatile
        private var instance: HistoryDatabaseHelper? = null

        fun getInstance(context: Context): HistoryDatabaseHelper {
            return instance ?: synchronized(this) {
                instance ?: HistoryDatabaseHelper(context).also { instance = it }
            }
        }
    }
}
