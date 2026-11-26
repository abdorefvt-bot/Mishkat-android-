package com.mishkat.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class MishkatNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != MishkatNotificationScheduler.ACTION_CONTENT_REMINDER) return
        MishkatNotificationScheduler.showContentReminder(context)
        MishkatNotificationScheduler.scheduleNextContentReminder(context)
    }
}