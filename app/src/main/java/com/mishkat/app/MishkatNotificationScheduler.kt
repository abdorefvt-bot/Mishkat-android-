package com.mishkat.app

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import org.json.JSONArray

object MishkatNotificationScheduler {
    private const val PREFS = "mishkat_notifications"
    private const val CHANNEL_REMINDERS = "mishkat_reminders"
    const val ACTION_CONTENT_REMINDER = "com.mishkat.app.notification.CONTENT_REMINDER"
    private const val ACTION_ADHAN = "com.mishkat.app.notification.ADHAN"
    private const val ACTION_MISSED = "com.mishkat.app.notification.MISSED"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_REMINDERS, "آيات وأذكار مشكاة", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "تنبيه بالتبادل بين آية قرآنية وذكر كل ساعتين"
                enableVibration(true)
                setShowBadge(false)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    fun setAdhkarEnabled(context: Context, enabled: Boolean) {
        val p = prefs(context)
        p.edit().putBoolean("content_reminders_enabled", enabled).apply()
        if (!enabled) {
            cancel(context, ACTION_CONTENT_REMINDER, "content")
            p.edit().remove("next_content_reminder_at").apply()
        } else scheduleNextContentReminder(context)
    }

    fun scheduleNextContentReminder(context: Context) {
        val p = prefs(context)
        if (!p.getBoolean("content_reminders_enabled", true)) return
        var nextAt = p.getLong("next_content_reminder_at", 0L)
        if (nextAt <= System.currentTimeMillis()) {
            nextAt = System.currentTimeMillis() + 2L * 60L * 60L * 1000L
            p.edit().putLong("next_content_reminder_at", nextAt).apply()
        }
        val intent = Intent(context, MishkatNotificationReceiver::class.java).setAction(ACTION_CONTENT_REMINDER)
        val pending = PendingIntent.getBroadcast(context, 9012, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val alarm = context.getSystemService(AlarmManager::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextAt, pending)
            else alarm.set(AlarmManager.RTC_WAKEUP, nextAt, pending)
        } catch (_: SecurityException) { }
    }

    fun showContentReminder(context: Context) {
        val p = prefs(context)
        if (!p.getBoolean("content_reminders_enabled", true)) return
        val sequence = p.getInt("content_sequence", 0)
        val verseSlot = sequence % 2 == 0
        p.edit().putInt("content_sequence", sequence + 1).apply()
        val asset = if (verseSlot) "verses.json" else "adhkar.json"
        val items = readItems(context, asset)
        if (items.isEmpty()) return
        val key = if (verseSlot) "verse_index" else "dhikr_index"
        val index = p.getInt(key, 0) % items.size
        p.edit().putInt(key, (index + 1) % items.size).apply()
        val text = items[index]
        val id = (System.currentTimeMillis() / 1000L).toInt()
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val contentIntent = launch?.let { PendingIntent.getActivity(context, id, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_mishkat)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try { context.getSystemService(NotificationManager::class.java).notify(id, notification) }
        catch (_: SecurityException) { }
    }

    fun restoreSchedules(context: Context) {
        // Remove prayer-time and missed-prayer alarms left by older versions.
        val p = prefs(context)
        val dates = p.getStringSet("scheduled_dates", emptySet()) ?: emptySet()
        for (date in dates) {
            for (prayer in listOf("Fajr", "Dhuhr", "Asr", "Maghrib", "Isha")) {
                cancel(context, ACTION_ADHAN, "$date-$prayer")
                cancel(context, ACTION_MISSED, "$date-$prayer")
                val manager = context.getSystemService(NotificationManager::class.java)
                manager.cancel(("$date-$prayer-$ACTION_ADHAN").hashCode())
                manager.cancel(("$date-$prayer-$ACTION_MISSED").hashCode())
            }
            p.edit().remove("day_$date").apply()
        }
        p.edit().remove("scheduled_dates").apply()
        cancel(context, "com.mishkat.app.notification.ADHKAR", "adhkar")
        if (p.getBoolean("content_reminders_enabled", true)) scheduleNextContentReminder(context)
    }

    private fun readItems(context: Context, asset: String): List<String> = try {
        val raw = context.assets.open(asset).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.optString("text")?.takeIf { it.isNotBlank() } }
    } catch (_: Exception) { emptyList() }

    private fun cancel(context: Context, action: String, key: String) {
        val intent = Intent(context, MishkatNotificationReceiver::class.java).setAction(action)
        val pending = PendingIntent.getBroadcast(context, ("$action:$key").hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        context.getSystemService(AlarmManager::class.java).cancel(pending)
        pending.cancel()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}