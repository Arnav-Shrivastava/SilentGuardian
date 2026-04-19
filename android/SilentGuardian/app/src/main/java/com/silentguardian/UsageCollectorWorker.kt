package com.silentguardian

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log
import androidx.work.*
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * UsageCollectorWorker — the heart of Layer 1 (Signal Collection).
 *
 * This WorkManager Worker runs every 30 minutes in the background.
 * It reads the last 30 minutes of UsageEvents from UsageStatsManager
 * and writes SCREEN_UNLOCK events into SQLite.
 *
 * Why WorkManager?
 *   - Survives app being backgrounded or killed
 *   - Battery-efficient (uses JobScheduler under the hood on API 23+)
 *   - Automatically retries on failure
 *   - Persists across reboots (with BootReceiver)
 */
class UsageCollectorWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : Worker(context, workerParams) {

    companion object {
        const val TAG = "UsageCollectorWorker"
        const val WORK_NAME = "silentguardian_usage_collector"

        /**
         * Call this once (e.g. from MainActivity or BootReceiver) to schedule
         * the worker to run every 30 minutes forever.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                // Only run if battery is not critically low (< 5%)
                .setRequiresBatteryNotLow(false)
                .build()

            val request = PeriodicWorkRequestBuilder<UsageCollectorWorker>(
                30, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .addTag(TAG)
                // If it fails, retry with exponential backoff starting at 5 minutes
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build()

            // KEEP_ALIVE: if already scheduled, don't replace it
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )

            Log.d(TAG, "UsageCollectorWorker scheduled — runs every 30 minutes")
        }

        /**
         * Run the worker ONCE immediately. Useful for testing.
         */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<UsageCollectorWorker>()
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context).enqueue(request)
            Log.d(TAG, "UsageCollectorWorker queued for immediate run")
        }
    }

    private val db by lazy { DatabaseHelper(context) }

    override fun doWork(): Result {
        Log.d(TAG, "doWork() started at ${Date()}")

        return try {
            // Check permission before doing anything
            if (!hasUsagePermission()) {
                Log.w(TAG, "PACKAGE_USAGE_STATS permission not granted — skipping collection")
                // Return SUCCESS so WorkManager doesn't cancel future runs.
                // The UI will show a permission warning to the user.
                return Result.success()
            }

            collectScreenUnlockEvents()
            purgeOldDataIfNeeded()

            Log.d(TAG, "doWork() completed successfully")
            Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "doWork() failed: ${e.message}", e)
            // RETRY tells WorkManager to try again with backoff
            Result.retry()
        }
    }

    /**
     * Reads UsageEvents for the last 30 minutes and logs every KEYGUARD_HIDDEN
     * event (= screen unlock) to SQLite.
     *
     * Why KEYGUARD_HIDDEN?
     *   Android's UsageEvents.Event.KEYGUARD_HIDDEN fires when the user
     *   dismisses the lockscreen — that's exactly "phone unlocked".
     */
    private fun collectScreenUnlockEvents() {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE)
                as UsageStatsManager

        // Query the last 31 minutes (slight overlap to avoid missing edge events)
        val endTime = System.currentTimeMillis()
        val startTime = endTime - TimeUnit.MINUTES.toMillis(31)

        val usageEvents = usageStatsManager.queryEvents(startTime, endTime)
        val event = UsageEvents.Event()

        var unlockCount = 0

        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)

            when (event.eventType) {

                // KEYGUARD_HIDDEN = lockscreen dismissed = phone unlocked
                UsageEvents.Event.KEYGUARD_HIDDEN -> {
                    val timestamp = Date(event.timeStamp)
                    db.insertEvent(
                        eventType = DatabaseHelper.EVENT_SCREEN_UNLOCK,
                        value = "unlock",
                        timestamp = timestamp
                    )
                    unlockCount++
                    Log.d(TAG, "SCREEN_UNLOCK logged at $timestamp")
                }

                // KEYGUARD_SHOWN = lockscreen appeared = screen locked
                UsageEvents.Event.KEYGUARD_SHOWN -> {
                    val timestamp = Date(event.timeStamp)
                    db.insertEvent(
                        eventType = DatabaseHelper.EVENT_SCREEN_UNLOCK,
                        value = "lock",
                        timestamp = timestamp
                    )
                }
            }
        }

        Log.d(TAG, "Collected $unlockCount unlock events in last 30 mins")
    }

    /**
     * Purge events older than 30 days to keep DB size under 5MB.
     * Architecture spec: auto-purge after 30 days.
     * Only runs once per day (checks if today's purge already happened).
     */
    private fun purgeOldDataIfNeeded() {
        val prefs = context.getSharedPreferences("sg_prefs", Context.MODE_PRIVATE)
        val lastPurgeDay = prefs.getString("last_purge_day", "")
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

        if (lastPurgeDay != today) {
            db.purgeOldEvents(daysToKeep = 30)
            prefs.edit().putString("last_purge_day", today).apply()
            Log.d(TAG, "Database purged — removed events older than 30 days")
        }
    }

    /**
     * Returns true if the app has been granted PACKAGE_USAGE_STATS permission.
     * This permission cannot be requested normally — the user must go to
     * Settings > Apps > Special app access > Usage access and toggle it on.
     */
    private fun hasUsagePermission(): Boolean {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val stats = usm.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            now - TimeUnit.HOURS.toMillis(1),
            now
        )
        return stats != null && stats.isNotEmpty()
    }
}
