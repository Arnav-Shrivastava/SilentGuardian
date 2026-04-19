package com.silentguardian

import android.Manifest
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.provider.CallLog
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.*
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Tasks
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * UsageCollectorWorker — the heart of Layer 1 (Signal Collection).
 * Runs every 30 minutes to collect comprehensive behavioral signals.
 */
class UsageCollectorWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : Worker(context, workerParams) {

    companion object {
        const val TAG = "UsageCollectorWorker"
        const val WORK_NAME = "silentguardian_usage_collector"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<UsageCollectorWorker>(
                30, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .addTag(TAG)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<UsageCollectorWorker>()
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }

    private val db by lazy { DatabaseHelper(context) }

    override fun doWork(): Result {
        Log.d(TAG, "doWork() started at ${Date()}")

        collectScreenUnlockEvents()
        collectCallLogs()
        collectBatteryStatus()
        collectCoarseLocation()
        purgeOldDataIfNeeded()

        return Result.success()
    }

    private fun collectScreenUnlockEvents() {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val endTime = System.currentTimeMillis()
        val startTime = endTime - TimeUnit.MINUTES.toMillis(31)

        val usageEvents = usm.queryEvents(startTime, endTime)
        val event = UsageEvents.Event()

        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.KEYGUARD_HIDDEN) {
                db.insertEvent(DatabaseHelper.EVENT_SCREEN_UNLOCK, "unlock", Date(event.timeStamp))
            } else if (event.eventType == UsageEvents.Event.KEYGUARD_SHOWN) {
                db.insertEvent(DatabaseHelper.EVENT_SCREEN_UNLOCK, "lock", Date(event.timeStamp))
            }
        }
    }

    private fun collectCallLogs() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return

        val cursor = context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            null,
            "${CallLog.Calls.DATE} > ?",
            arrayOf((System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(31)).toString()),
            "${CallLog.Calls.DATE} DESC"
        )

        cursor?.use {
            while (it.moveToNext()) {
                val type = it.getInt(it.getColumnIndexOrThrow(CallLog.Calls.TYPE))
                val duration = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.DURATION))
                val date = it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DATE))
                val typeStr = when (type) {
                    CallLog.Calls.INCOMING_TYPE -> "INCOMING"
                    CallLog.Calls.OUTGOING_TYPE -> "OUTGOING"
                    CallLog.Calls.MISSED_TYPE -> "MISSED"
                    else -> "OTHER"
                }
                db.insertEvent(DatabaseHelper.EVENT_CALL, "$typeStr, duration: $duration", Date(date))
            }
        }
    }

    private fun collectBatteryStatus() {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, filter)
        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        
        val chargePlug = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val usbCharge = chargePlug == BatteryManager.BATTERY_PLUGGED_USB
        val acCharge = chargePlug == BatteryManager.BATTERY_PLUGGED_AC
        
        val value = if (isCharging) "CHARGING (${if (acCharge) "AC" else if (usbCharge) "USB" else "OTHER"})" else "DISCHARGING"
        db.insertEvent(DatabaseHelper.EVENT_CHARGE, value)
    }

    private fun collectCoarseLocation() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return

        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
        try {
            val task = fusedLocationClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
            val location = Tasks.await(task, 10, TimeUnit.SECONDS)
            location?.let {
                db.insertEvent(DatabaseHelper.EVENT_LOCATION, "lat: ${it.latitude}, lon: ${it.longitude}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Location collection failed", e)
        }
    }

    private fun purgeOldDataIfNeeded() {
        val prefs = context.getSharedPreferences("sg_prefs", Context.MODE_PRIVATE)
        val lastPurgeDay = prefs.getString("last_purge_day", "")
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

        if (lastPurgeDay != today) {
            db.purgeOldEvents(30)
            prefs.edit().putString("last_purge_day", today).apply()
        }
    }
}
