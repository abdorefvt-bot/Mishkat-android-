package com.mishkat.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class MishkatNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            "com.mishkat.app.notification.ADHAN",
            "com.mishkat.app.notification.MISSED" -> {
                val action = intent.action ?: return
                val date = intent.getStringExtra("date") ?: return
                val key = intent.getStringExtra("prayer_key") ?: return
                val label = intent.getStringExtra("label") ?: key
                if (action == "com.mishkat.app.notification.MISSED") {
                    val prefs = context.getSharedPreferences("mishkat_notifications", Context.MODE_PRIVATE)
                    if (prefs.getBoolean("completed_$date-$key", false)) return
                }
                MishkatNotificationScheduler.showPrayerIfBackground(context, action, date, key, label)
            }
            MishkatNotificationScheduler.ACTION_ADHKAR -> {
                MishkatNotificationScheduler.showAdhkarIfBackground(context)
                MishkatNotificationScheduler.scheduleNextAdhkar(context)
            }
        }
    }
}
