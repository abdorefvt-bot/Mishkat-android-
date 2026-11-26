package com.mishkat.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import android.app.NotificationChannel
import android.app.NotificationManager
import org.json.JSONArray

object MishkatReminders {
    private const val PREFS = "mishkat_reminders_v1"
    private const val KEY_INDEX = "index"
    private const val KEY_STARTED = "started"
    private const val REQUEST = 8127
    private const val CHANNEL = "mishkat_verse_dhikr"

    fun scheduleFirstIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        createChannel(context)
        if (!prefs.getBoolean(KEY_STARTED, false)) {
            prefs.edit().putBoolean(KEY_STARTED, true).apply()
            scheduleNext(context)
        }
    }

    fun scheduleNext(context: Context) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, MishkatReminderReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            context, REQUEST, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 2 * 60 * 60 * 1000L, pending)
    }

    fun showNext(context: Context) {
        createChannel(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val index = prefs.getInt(KEY_INDEX, 0)
        val verse = index % 2 == 0
        val itemIndex = index / 2
        val list = readList(context, if (verse) "verses.json" else "adhkar.json")
        if (list.isNotEmpty()) {
            val item = list[itemIndex % list.size]
            val title = if (verse) "آية قرآنية" else "ذِكر"
            val builder = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_mishkat)
                .setContentTitle(title)
                .setContentText(item)
                .setStyle(NotificationCompat.BigTextStyle().bigText(item))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(PendingIntent.getActivity(
                    context, 990, Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                ))
            try { NotificationManagerCompat.from(context).notify(9201, builder.build()) } catch (_: SecurityException) { }
            prefs.edit().putInt(KEY_INDEX, index + 1).apply()
        }
        scheduleNext(context)
    }

    private fun readList(context: Context, file: String): List<String> = try {
        val array = JSONArray(context.assets.open(file).bufferedReader(Charsets.UTF_8).use { it.readText() })
        (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.optString("text")?.takeIf { it.isNotBlank() } }
    } catch (_: Exception) { emptyList() }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL, "آيات وأذكار مشكات", NotificationManager.IMPORTANCE_DEFAULT)
            channel.description = "إشعار كل ساعتين بالتناوب بين آية وذكر"
            channel.setShowBadge(false)
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}

class MishkatReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        MishkatReminders.showNext(context)
    }
}

class MishkatBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        MishkatReminders.scheduleFirstIfNeeded(context)
        MishkatReminders.scheduleNext(context)
    }
}