package com.mishkat.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class MishkatNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val received = intent ?: return
        when (received.action) {
            "com.mishkat.app.notification.ADHAN",
            "com.mishkat.app.notification.MISSED" -> {
                val action = received.action ?: return
                val date = received.getStringExtra("date") ?: return
                val key = received.getStringExtra("prayer_key") ?: return
                val label = received.getStringExtra("label") ?: key
                if (action == "com.mishkat.app.notification.MISSED") {
                    val prefs = context.getSharedPreferences("mishkat_notifications", Context.MODE_PRIVATE)
                    if (prefs.getBoolean("completed_$date-$key", false)) return
                }
                MishkatNotificationScheduler.showPrayerIfBackground(context, action, date, key, label)
            }
            MishkatNotificationScheduler.ACTION_CONTENT_REMINDER -> {
                MishkatNotificationScheduler.showContentReminder(context)
                MishkatNotificationScheduler.scheduleNextContentReminder(context)
            }
        }
    }
}
