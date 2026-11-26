package com.mishkat.app

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject

object MishkatNotificationScheduler {
    private const val PREFS = "mishkat_notifications"
    private const val CHANNEL_REMINDERS = "mishkat_reminders"
    private const val ACTION_ADHAN = "com.mishkat.app.notification.ADHAN"
    private const val ACTION_MISSED = "com.mishkat.app.notification.MISSED"
    const val ACTION_CONTENT_REMINDER = "com.mishkat.app.notification.CONTENT_REMINDER"

    private val prayerNames = mapOf(
        "Fajr" to "الفجر",
        "Dhuhr" to "الظهر",
        "Asr" to "العصر",
        "Maghrib" to "المغرب",
        "Isha" to "العشاء"
    )

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val reminders = NotificationChannel(
                CHANNEL_REMINDERS,
                "تذكيرات مشكاة",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "تذكيرات الأذكار ومواقيت الصلاة ومتابعتها"
                enableVibration(true)
                setShowBadge(false)
            }
            manager.createNotificationChannel(reminders)
        }
    }

    fun setAppInBackground(context: Context, inBackground: Boolean) {
        prefs(context).edit().putBoolean("app_in_background", inBackground).apply()
    }

    // Existing website bridge entry point; it now controls the unified
    // two-hour alternating Quran/adhkar reminder cycle.
    fun setAdhkarEnabled(context: Context, enabled: Boolean) {
        val p = prefs(context)
        val wasEnabled = p.getBoolean("content_reminders_enabled", false)
        p.edit().putBoolean("content_reminders_enabled", enabled).apply()
        if (!enabled) {
            cancel(context, ACTION_CONTENT_REMINDER, "content")
            p.edit().remove("next_content_reminder_at").apply()
        } else if (!wasEnabled || p.getLong("next_content_reminder_at", 0L) <= System.currentTimeMillis()) {
            scheduleNextContentReminder(context)
        } else {
            scheduleNextContentReminder(context)
        }
    }

    fun scheduleNextContentReminder(context: Context) {
        val p = prefs(context)
        if (!p.getBoolean("content_reminders_enabled", true)) return
        val now = System.currentTimeMillis()
        var nextAt = p.getLong("next_content_reminder_at", 0L)
        if (nextAt <= now) {
            nextAt = now + 2L * 60L * 60L * 1000L
            p.edit().putLong("next_content_reminder_at", nextAt).apply()
        }
        schedule(context, ACTION_CONTENT_REMINDER, "content", nextAt, "", "", "")
    }

    fun showContentReminder(context: Context) {
        val p = prefs(context)
        if (!p.getBoolean("content_reminders_enabled", true)) return
        val sequence = p.getInt("content_sequence", 0)
        val verseSlot = sequence % 2 == 0
        p.edit().putInt("content_sequence", sequence + 1).apply()
        val content = if (verseSlot) {
            val verses = readTextItems(context, "verses.json")
            if (verses.isEmpty()) return
            val index = p.getInt("verse_index", 0) % verses.size
            p.edit().putInt("verse_index", (index + 1) % verses.size).apply()
            verses[index]
        } else {
            val adhkar = readTextItems(context, "adhkar.json")
            if (adhkar.isEmpty()) return
            val index = p.getInt("dhikr_index", 0) % adhkar.size
            p.edit().putInt("dhikr_index", (index + 1) % adhkar.size).apply()
            adhkar[index]
        }
        val id = (System.currentTimeMillis() / 1000L).toInt()
        showContentNotification(context, id, content)
    }

    private fun readTextItems(context: Context, assetName: String): List<String> {
        return try {
            val raw = context.assets.open(assetName).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.optString("text")?.takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun schedulePrayerDay(
        context: Context,
        date: String,
        timeZoneId: String,
        timesJson: String,
        completedJson: String
    ) {
        val times = try { JSONObject(timesJson) } catch (_: Exception) { return }
        val completed = try { JSONObject(completedJson) } catch (_: Exception) { JSONObject() }
        val day = JSONObject().apply {
            put("date", date)
            put("timeZone", timeZoneId)
            put("times", times)
            put("completed", completed)
        }
        val p = prefs(context)
        p.edit().putString("time_zone", timeZoneId).putString("day_$date", day.toString()).apply()
        for (key in prayerNames.keys) markCompleted(context, date, key, completed.optBoolean(key, false))
        val dates = (p.getStringSet("scheduled_dates", emptySet()) ?: emptySet()).toMutableSet()
        dates.add(date)
        p.edit().putStringSet("scheduled_dates", dates).apply()
        scheduleDay(context, day)
    }

    fun updatePrayerStatuses(context: Context, date: String, completedJson: String) {
        val p = prefs(context)
        val day = try { JSONObject(p.getString("day_$date", null) ?: return) } catch (_: Exception) { return }
        val completed = try { JSONObject(completedJson) } catch (_: Exception) { return }
        day.put("completed", completed)
        p.edit().putString("day_$date", day.toString()).apply()
        for (key in prayerNames.keys) {
            val isComplete = completed.optBoolean(key, false)
            markCompleted(context, date, key, isComplete)
            val requestKey = "$date-$key"
            if (isComplete) {
                cancel(context, ACTION_MISSED, requestKey)
                cancelNotification(context, ("$date-$key-$ACTION_MISSED").hashCode())
            } else {
                val time = prayerInstant(day, key) ?: continue
                val missedAt = time + 30L * 60L * 1000L
                if (missedAt > System.currentTimeMillis()) {
                    schedule(context, ACTION_MISSED, requestKey, missedAt, prayerNames[key] ?: key, date, key)
                }
            }
        }
    }

    private fun scheduleDay(context: Context, day: JSONObject) {
        val date = day.optString("date")
        val completed = day.optJSONObject("completed") ?: JSONObject()
        for (key in prayerNames.keys) {
            val requestKey = "$date-$key"
            val isComplete = completed.optBoolean(key, false)
            markCompleted(context, date, key, isComplete)
            if (isComplete) cancelNotification(context, ("$date-$key-$ACTION_MISSED").hashCode())
            cancel(context, ACTION_ADHAN, requestKey)
            cancel(context, ACTION_MISSED, requestKey)
            val prayerAt = prayerInstant(day, key) ?: continue
            if (prayerAt > System.currentTimeMillis()) {
                schedule(context, ACTION_ADHAN, requestKey, prayerAt, prayerNames[key] ?: key, date, key)
            }
            val missedAt = prayerAt + 30L * 60L * 1000L
            if (!completed.optBoolean(key, false) && missedAt > System.currentTimeMillis()) {
                schedule(context, ACTION_MISSED, requestKey, missedAt, prayerNames[key] ?: key, date, key)
            }
        }
    }

    private fun prayerInstant(day: JSONObject, key: String): Long? {
        val time = day.optJSONObject("times")?.optString(key).orEmpty()
        val match = Regex("(\\d{1,2}):(\\d{2})").find(time) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        val zone = TimeZone.getTimeZone(day.optString("timeZone", "Africa/Cairo"))
        val date = try {
            SimpleDateFormat("dd-MM-yyyy", Locale.US).apply {
                isLenient = false
                timeZone = zone
            }.parse(day.optString("date")) ?: return null
        } catch (_: Exception) { return null }
        return Calendar.getInstance(zone).apply {
            this.time = date
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    fun setTimeZone(context: Context, timeZoneId: String) {
        prefs(context).edit().putString("time_zone", timeZoneId).apply()
    }

    fun restoreSchedules(context: Context) {
        val p = prefs(context)
        val dates = (p.getStringSet("scheduled_dates", emptySet()) ?: emptySet()).toList()
        val keep = mutableSetOf<String>()
        for (date in dates) {
            val raw = p.getString("day_$date", null) ?: continue
            try {
                val day = JSONObject(raw)
                val parsed = SimpleDateFormat("dd-MM-yyyy", Locale.US).parse(date)
                if (parsed != null && parsed.time >= System.currentTimeMillis() - 24L * 60L * 60L * 1000L) {
                    keep.add(date)
                    scheduleDay(context, day)
                } else {
                    p.edit().remove("day_$date").apply()
                }
            } catch (_: Exception) { }
        }
        p.edit().putStringSet("scheduled_dates", keep).apply()
        // Cancel any one-time alarm from the previous 30-minute reminder system.
        cancel(context, "com.mishkat.app.notification.ADHKAR", "adhkar")
        if (p.getBoolean("content_reminders_enabled", true)) scheduleNextContentReminder(context)
    }

    fun showPrayerIfBackground(context: Context, action: String, date: String, key: String, label: String) {
        val p = prefs(context)
        if (!p.getBoolean("app_in_background", true)) return
        if (action == ACTION_MISSED && p.getBoolean("completed_$date-$key", false)) return
        val title = if (action == ACTION_ADHAN) "حان الآن موعد أذان $label" else "لم يتم تسجيل صلاة $label"
        val body = if (action == ACTION_ADHAN) "حيّ على الصلاة، حيّ على الفلاح" else "إذا أتممت الصلاة، افتح مشكاة وسجّلها في متابعة الصلوات."
        show(context, ("$date-$key-$action").hashCode(), title, body)
    }

    fun markCompleted(context: Context, date: String, key: String, completed: Boolean) {
        prefs(context).edit().putBoolean("completed_$date-$key", completed).apply()
    }

    private fun showContentNotification(context: Context, id: Int, text: String) {
        createChannels(context)
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val contentIntent = launch?.let {
            PendingIntent.getActivity(
                context, id, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
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
        try {
            context.getSystemService(NotificationManager::class.java).notify(id, notification)
        } catch (_: SecurityException) { }
    }

    private fun show(context: Context, id: Int, title: String, body: String) {
        createChannels(context)
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val contentIntent = launch?.let {
            PendingIntent.getActivity(
                context, id, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_mishkat)
            .setContentTitle(title.takeIf { it.isNotBlank() })
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            context.getSystemService(NotificationManager::class.java).notify(id, notification)
        } catch (_: SecurityException) { }
    }

    private fun schedule(
        context: Context,
        action: String,
        key: String,
        atMillis: Long,
        label: String,
        date: String,
        prayerKey: String
    ) {
        val intent = Intent(context, MishkatNotificationReceiver::class.java).apply {
            this.action = action
            putExtra("key", key)
            putExtra("label", label)
            putExtra("date", date)
            putExtra("prayer_key", prayerKey)
        }
        val requestCode = ("$action:$key").hashCode()
        val pending = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarm = context.getSystemService(AlarmManager::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarm.canScheduleExactAlarms()) {
                    alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
                } else {
                    alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
                }
            } else {
                alarm.setExact(AlarmManager.RTC_WAKEUP, atMillis, pending)
            }
        } catch (_: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
            } else {
                alarm.set(AlarmManager.RTC_WAKEUP, atMillis, pending)
            }
        }
    }

    private fun cancel(context: Context, action: String, key: String) {
        val intent = Intent(context, MishkatNotificationReceiver::class.java).apply { this.action = action }
        val pending = PendingIntent.getBroadcast(
            context, ("$action:$key").hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        context.getSystemService(AlarmManager::class.java).cancel(pending)
        pending.cancel()
    }

    private fun cancelNotification(context: Context, id: Int) {
        context.getSystemService(NotificationManager::class.java).cancel(id)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
