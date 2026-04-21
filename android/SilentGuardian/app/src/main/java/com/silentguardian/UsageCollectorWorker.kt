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
import android.telephony.*
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.*
import java.util.*
import java.util.concurrent.TimeUnit

class UsageCollectorWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : Worker(context, workerParams) {

    companion object {
        const val TAG = "UsageCollectorWorker"
        const val WORK_NAME = "silentguardian_usage_collector"
        private const val PREFS_NAME = "sg_prefs"
        private const val KEY_HOME_TOWER = "home_tower_id"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<UsageCollectorWorker>(30, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .addTag(TAG)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<UsageCollectorWorker>().build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }

    private val db by lazy { DatabaseHelper(context) }

    override fun doWork(): Result {
        collectScreenUnlockEvents()
        collectCallLogs()
        collectBatteryStatus()
        collectCellLocation()
        return Result.success()
    }

    private fun collectScreenUnlockEvents() {
        try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val endTime = System.currentTimeMillis()
            val startTime = endTime - TimeUnit.MINUTES.toMillis(31)

            val usageEvents = usm.queryEvents(startTime, endTime)
            val event = UsageEvents.Event()

            while (usageEvents.hasNextEvent()) {
                usageEvents.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.KEYGUARD_HIDDEN) {
                    db.insertEvent(DatabaseHelper.EVENT_SCREEN_UNLOCK, "unlock", null, Date(event.timeStamp))
                } else if (event.eventType == UsageEvents.Event.KEYGUARD_SHOWN) {
                    db.insertEvent(DatabaseHelper.EVENT_SCREEN_UNLOCK, "lock", null, Date(event.timeStamp))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Screen unlock collection failed", e)
        }
    }

    private fun collectCallLogs() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return

        try {
            val cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.TYPE, CallLog.Calls.DURATION, CallLog.Calls.DATE),
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
                        CallLog.Calls.INCOMING_TYPE -> "incoming"
                        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                        else -> null
                    }
                    
                    if (typeStr != null) {
                        db.insertEvent(DatabaseHelper.EVENT_CALL, typeStr, "duration_seconds:$duration", Date(date))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Call log collection failed", e)
        }
    }

    private fun collectBatteryStatus() {
        try {
            val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            batteryStatus?.let { intent ->
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val pct = (level * 100 / scale.toFloat()).toInt()
                
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                
                db.insertEvent(DatabaseHelper.EVENT_CHARGE, pct.toString(), if (isCharging) "charging" else "discharging")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Battery collection failed", e)
        }
    }

    private fun collectCellLocation() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return

        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val allCellInfo = tm.allCellInfo
            if (allCellInfo.isNullOrEmpty()) return

            val cellInfo = allCellInfo[0]
            val towerId = getTowerId(cellInfo) ?: return

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val homeTower = prefs.getString(KEY_HOME_TOWER, null)

            if (homeTower == null) {
                prefs.edit().putString(KEY_HOME_TOWER, towerId).apply()
                db.insertEvent(DatabaseHelper.EVENT_LOCATION, "home", towerId)
            } else {
                val status = if (homeTower == towerId) "home" else "away"
                db.insertEvent(DatabaseHelper.EVENT_LOCATION, status, towerId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cell location collection failed", e)
        }
    }

    private fun getTowerId(info: CellInfo): String? {
        return when (info) {
            is CellInfoGsm -> {
                val id = info.cellIdentity
                "${id.mccString}-${id.mncString}-${id.lac}-${id.cid}"
            }
            is CellInfoWcdma -> {
                val id = info.cellIdentity
                "${id.mccString}-${id.mncString}-${id.lac}-${id.cid}"
            }
            is CellInfoLte -> {
                val id = info.cellIdentity
                "${id.mccString}-${id.mncString}-${id.tac}-${id.ci}"
            }
            else -> null
        }
    }
}
