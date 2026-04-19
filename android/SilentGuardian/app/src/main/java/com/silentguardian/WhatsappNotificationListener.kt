package com.silentguardian

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Listens for incoming notifications to count WhatsApp activity.
 * This is a real-time signal of social engagement.
 */
class WhatsappNotificationListener : NotificationListenerService() {

    companion object {
        const val TAG = "NotificationListener"
        const val WHATSAPP_PKG = "com.whatsapp"
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == WHATSAPP_PKG) {
            Log.d(TAG, "WhatsApp notification detected")
            val db = DatabaseHelper(applicationContext)
            db.insertEvent(
                eventType = DatabaseHelper.EVENT_NOTIFICATION,
                value = "WhatsApp"
            )
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Optional: track dismissal if needed
    }
}
