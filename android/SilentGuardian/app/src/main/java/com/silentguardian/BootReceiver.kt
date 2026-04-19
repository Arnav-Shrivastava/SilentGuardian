package com.silentguardian

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * BootReceiver — reschedules the UsageCollectorWorker after device reboot.
 *
 * WorkManager periodic work persists across reboots automatically on most devices,
 * but this receiver is a belt-and-suspenders guarantee. When the phone boots,
 * this fires and calls schedule() — WorkManager's KEEP policy means if it's
 * already scheduled, nothing changes.
 *
 * Requires: android.permission.RECEIVE_BOOT_COMPLETED in AndroidManifest.xml
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootReceiver", "Device rebooted — rescheduling UsageCollectorWorker")
            UsageCollectorWorker.schedule(context)
        }
    }
}
