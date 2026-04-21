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
 * Schema (Updated):
 *   TABLE: events
 *   - id            INTEGER  PRIMARY KEY AUTOINCREMENT
 *   - timestamp     TEXT     ISO-8601 datetime string
 *   - event_type    TEXT     e.g. "SCREEN_UNLOCK", "CALL", "CHARGE", "LOCATION", "NOTIFICATION"
 *   - primary_val   TEXT
 *   - secondary_val TEXT
 *   - day_of_week   TEXT     e.g. "MONDAY"
 */
class DatabaseHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "silentguardian.db"
        const val DATABASE_VERSION = 2

        const val TABLE_EVENTS = "events"
        const val COL_ID = "id"
        const val COL_TIMESTAMP = "timestamp"
        const val COL_EVENT_TYPE = "event_type"
        const val COL_PRIMARY_VAL = "primary_val"
        const val COL_SECONDARY_VAL = "secondary_val"
        const val COL_DAY_OF_WEEK = "day_of_week"

        const val EVENT_SCREEN_UNLOCK = "SCREEN_UNLOCK"
        const val EVENT_APP_USAGE = "APP_USAGE"
        const val EVENT_CALL = "CALL"
        const val EVENT_CHARGE = "CHARGE"
        const val EVENT_LOCATION = "LOCATION"
        const val EVENT_NOTIFICATION = "NOTIFICATION"
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val dayFormat = SimpleDateFormat("EEEE", Locale.getDefault())

    override fun onCreate(db: SQLiteDatabase) {
        val createTable = """
            CREATE TABLE $TABLE_EVENTS (
                $COL_ID            INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_TIMESTAMP     TEXT    NOT NULL,
                $COL_EVENT_TYPE    TEXT    NOT NULL,
                $COL_PRIMARY_VAL   TEXT,
                $COL_SECONDARY_VAL TEXT,
                $COL_DAY_OF_WEEK   TEXT    NOT NULL
            )
        """.trimIndent()
        db.execSQL(createTable)
        db.execSQL("CREATE INDEX idx_event_type ON $TABLE_EVENTS ($COL_EVENT_TYPE)")
        db.execSQL("CREATE INDEX idx_timestamp  ON $TABLE_EVENTS ($COL_TIMESTAMP)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_EVENTS")
        onCreate(db)
    }

    fun insertEvent(
        eventType: String,
        primaryVal: String? = null,
        secondaryVal: String? = null,
        timestamp: Date = Date()
    ): Long {
        val cv = ContentValues().apply {
            put(COL_TIMESTAMP, dateFormat.format(timestamp))
            put(COL_EVENT_TYPE, eventType)
            put(COL_PRIMARY_VAL, primaryVal ?: "")
            put(COL_SECONDARY_VAL, secondaryVal ?: "")
            put(COL_DAY_OF_WEEK, dayFormat.format(timestamp).uppercase())
        }
        return writableDatabase.insert(TABLE_EVENTS, null, cv)
    }

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
                        primaryVal = it.getString(it.getColumnIndexOrThrow(COL_PRIMARY_VAL)),
                        secondaryVal = it.getString(it.getColumnIndexOrThrow(COL_SECONDARY_VAL)),
                        dayOfWeek = it.getString(it.getColumnIndexOrThrow(COL_DAY_OF_WEEK))
                    )
                )
            }
        }
        return results
    }

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
                        primaryVal = it.getString(it.getColumnIndexOrThrow(COL_PRIMARY_VAL)),
                        secondaryVal = it.getString(it.getColumnIndexOrThrow(COL_SECONDARY_VAL)),
                        dayOfWeek = it.getString(it.getColumnIndexOrThrow(COL_DAY_OF_WEEK))
                    )
                )
            }
        }
        return results
    }

    fun purgeOldEvents(daysToKeep: Int = 30) {
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -daysToKeep) }
        val cutoff = dateFormat.format(cal.time)
        writableDatabase.delete(TABLE_EVENTS, "$COL_TIMESTAMP < ?", arrayOf(cutoff))
    }
}

data class EventRow(
    val id: Long,
    val timestamp: String,
    val eventType: String,
    val primaryVal: String,
    val secondaryVal: String,
    val dayOfWeek: String
)
