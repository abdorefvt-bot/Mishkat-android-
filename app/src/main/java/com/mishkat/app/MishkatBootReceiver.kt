package com.mishkat.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class MishkatBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" -> {
                MishkatNotificationScheduler.restoreSchedules(context)
            }
        }
    }
}
