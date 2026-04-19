package com.silentguardian

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.*

/**
 * DatabaseHelper manages the SQLite database for SilentGuardian.
 *
 * Schema (matches the roadmap spec exactly):
 *   TABLE: events
 *   - id          INTEGER  PRIMARY KEY AUTOINCREMENT
 *   - timestamp   TEXT     ISO-8601 datetime string  e.g. "2026-04-19 07:42:00"
 *   - event_type  TEXT     e.g. "SCREEN_UNLOCK", "APP_USAGE", "CALL", "CHARGE"
 *   - value       TEXT     event-specific payload    e.g. app name, call duration
 *   - day_of_week TEXT     e.g. "MONDAY"
 */
class DatabaseHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "silentguardian.db"
        const val DATABASE_VERSION = 1

        const val TABLE_EVENTS = "events"
        const val COL_ID = "id"
        const val COL_TIMESTAMP = "timestamp"
        const val COL_EVENT_TYPE = "event_type"
        const val COL_VALUE = "value"
        const val COL_DAY_OF_WEEK = "day_of_week"

        // Event type constants — use these everywhere so strings stay consistent
        const val EVENT_SCREEN_UNLOCK = "SCREEN_UNLOCK"
        const val EVENT_APP_USAGE = "APP_USAGE"
        const val EVENT_CALL = "CALL"
        const val EVENT_CHARGE = "CHARGE"
        const val EVENT_LOCATION = "LOCATION"
        const val EVENT_NOTIFICATION = "NOTIFICATION"
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val dayFormat = SimpleDateFormat("EEEE", Locale.getDefault()) // e.g. "MONDAY"

    override fun onCreate(db: SQLiteDatabase) {
        val createTable = """
            CREATE TABLE $TABLE_EVENTS (
                $COL_ID         INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_TIMESTAMP  TEXT    NOT NULL,
                $COL_EVENT_TYPE TEXT    NOT NULL,
                $COL_VALUE      TEXT,
                $COL_DAY_OF_WEEK TEXT   NOT NULL
            )
        """.trimIndent()
        db.execSQL(createTable)

        // Index on event_type so queries like "all unlocks today" are fast
        db.execSQL("CREATE INDEX idx_event_type ON $TABLE_EVENTS ($COL_EVENT_TYPE)")
        db.execSQL("CREATE INDEX idx_timestamp  ON $TABLE_EVENTS ($COL_TIMESTAMP)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Simple strategy for now: drop and recreate.
        // In production you'd write migration scripts instead.
        db.execSQL("DROP TABLE IF EXISTS $TABLE_EVENTS")
        onCreate(db)
    }

    /**
     * Insert a single event. Call this from your workers and receivers.
     *
     * @param eventType  One of the EVENT_* constants above
     * @param value      Optional payload string (e.g. package name, call duration)
     * @param timestamp  Defaults to right now
     */
    fun insertEvent(
        eventType: String,
        value: String? = null,
        timestamp: Date = Date()
    ): Long {
        val cv = ContentValues().apply {
            put(COL_TIMESTAMP, dateFormat.format(timestamp))
            put(COL_EVENT_TYPE, eventType)
            put(COL_VALUE, value ?: "")
            put(COL_DAY_OF_WEEK, dayFormat.format(timestamp).uppercase())
        }
        return writableDatabase.insert(TABLE_EVENTS, null, cv)
    }

    /**
     * Returns the N most recent events, newest first.
     * Used by the UI to show the last 10 unlock events.
     */
    fun getRecentEvents(eventType: String? = null, limit: Int = 10): List<EventRow> {
        val selection = if (eventType != null) "$COL_EVENT_TYPE = ?" else null
        val selectionArgs = if (eventType != null) arrayOf(eventType) else null

        val cursor = readableDatabase.query(
            TABLE_EVENTS,
            null,
            selection,
            selectionArgs,
            null,
            null,
            "$COL_TIMESTAMP DESC",
            "$limit"
        )

        val results = mutableListOf<EventRow>()
        cursor.use {
            while (it.moveToNext()) {
                results.add(
                    EventRow(
                        id = it.getLong(it.getColumnIndexOrThrow(COL_ID)),
                        timestamp = it.getString(it.getColumnIndexOrThrow(COL_TIMESTAMP)),
                        eventType = it.getString(it.getColumnIndexOrThrow(COL_EVENT_TYPE)),
                        value = it.getString(it.getColumnIndexOrThrow(COL_VALUE)),
                        dayOfWeek = it.getString(it.getColumnIndexOrThrow(COL_DAY_OF_WEEK))
                    )
                )
            }
        }
        return results
    }

    /**
     * Returns all events for a specific calendar date (YYYY-MM-DD).
     * Used by Agent 1 (Routine Modeller) to build daily feature vectors.
     */
    fun getEventsForDate(date: String): List<EventRow> {
        val cursor = readableDatabase.query(
            TABLE_EVENTS,
            null,
            "$COL_TIMESTAMP LIKE ?",
            arrayOf("$date%"),
            null, null,
            "$COL_TIMESTAMP ASC"
        )
        val results = mutableListOf<EventRow>()
        cursor.use {
            while (it.moveToNext()) {
                results.add(
                    EventRow(
                        id = it.getLong(it.getColumnIndexOrThrow(COL_ID)),
                        timestamp = it.getString(it.getColumnIndexOrThrow(COL_TIMESTAMP)),
                        eventType = it.getString(it.getColumnIndexOrThrow(COL_EVENT_TYPE)),
                        value = it.getString(it.getColumnIndexOrThrow(COL_VALUE)),
                        dayOfWeek = it.getString(it.getColumnIndexOrThrow(COL_DAY_OF_WEEK))
                    )
                )
            }
        }
        return results
    }

    /**
     * Deletes events older than [daysToKeep] days.
     * Architecture spec: auto-purge after 30 days to keep DB < 5MB.
     */
    fun purgeOldEvents(daysToKeep: Int = 30) {
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -daysToKeep) }
        val cutoff = dateFormat.format(cal.time)
        writableDatabase.delete(TABLE_EVENTS, "$COL_TIMESTAMP < ?", arrayOf(cutoff))
    }
}

/**
 * Plain data class representing one row from the events table.
 */
data class EventRow(
    val id: Long,
    val timestamp: String,
    val eventType: String,
    val value: String,
    val dayOfWeek: String
)
