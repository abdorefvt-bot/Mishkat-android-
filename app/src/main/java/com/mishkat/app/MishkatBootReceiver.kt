package com.mishkat.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class MishkatBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> MishkatNotificationScheduler.restoreSchedules(context)
        }
    }
}