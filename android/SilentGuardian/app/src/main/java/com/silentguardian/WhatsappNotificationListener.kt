package com.silentguardian

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class WhatsappNotificationListener : NotificationListenerService() {

    companion object {
        const val WHATSAPP_PKG = "com.whatsapp"
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == WHATSAPP_PKG) {
            val db = DatabaseHelper(applicationContext)
            db.insertEvent(
                eventType = DatabaseHelper.EVENT_NOTIFICATION,
                primaryVal = "1",
                secondaryVal = "whatsapp"
            )
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Not used, but required for older API compatibility override
    }
}
