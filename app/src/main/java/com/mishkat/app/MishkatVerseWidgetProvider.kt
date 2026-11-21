package com.mishkat.app
import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import org.json.JSONArray
import java.util.Calendar

class MishkatVerseWidgetProvider : AppWidgetProvider() {
 override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
  updateWidgets(context, false)
  scheduleHourlyUpdate(context)
 }
 override fun onEnabled(context: Context) { scheduleHourlyUpdate(context) }
 override fun onDisabled(context: Context) { context.getSystemService(AlarmManager::class.java).cancel(pending(context)) }
 override fun onReceive(context: Context, intent: Intent?) {
  super.onReceive(context, intent)
  if (intent?.action == ACTION_NEXT) {
   updateWidgets(context, true)
   scheduleHourlyUpdate(context)
  }
 }
 companion object {
  private const val PREFS="mishkat_verse_widget"
  private const val KEY_INDEX="verse_index"
  private const val ACTION_NEXT="com.mishkat.app.widget.NEXT_VERSE"
  private const val REQUEST=7812
  fun scheduleHourlyUpdate(context: Context) {
   val manager=AppWidgetManager.getInstance(context)
   if(manager.getAppWidgetIds(ComponentName(context,MishkatVerseWidgetProvider::class.java)).isEmpty()) return
   val next=Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY,1); set(Calendar.MINUTE,0); set(Calendar.SECOND,0); set(Calendar.MILLISECOND,0) }.timeInMillis
   val alarm=context.getSystemService(AlarmManager::class.java)
   val operation=pending(context)
   if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarm.canScheduleExactAlarms()) {
    alarm.setAndAllowWhileIdle(AlarmManager.RTC,next,operation)
   } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
    alarm.setExactAndAllowWhileIdle(AlarmManager.RTC,next,operation)
   } else {
    alarm.setExact(AlarmManager.RTC,next,operation)
   }
  }
  fun updateWidgets(context: Context, advance: Boolean) {
   val manager=AppWidgetManager.getInstance(context)
   val ids=manager.getAppWidgetIds(ComponentName(context,MishkatVerseWidgetProvider::class.java))
   if(ids.isEmpty()) return
   val verses=try {
    val raw=context.assets.open("verses.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
    val array=JSONArray(raw)
    (0 until array.length()).mapNotNull { i ->
     val item=array.optJSONObject(i) ?: return@mapNotNull null
     val text=item.optString("text").trim(); val source=item.optString("source").trim()
     if(text.isBlank() || source.isBlank()) null else Verse(text,source)
    }
   } catch (_:Exception) { emptyList() }
   if(verses.isEmpty()) return
   val prefs=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
   var index=prefs.getInt(KEY_INDEX,-1)
   if(index<0) index=0 else if(advance) index=(index+1)%verses.size
   prefs.edit().putInt(KEY_INDEX,index).apply()
   val verse=verses[index]
   val launch=context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP) }
   val click=launch?.let { PendingIntent.getActivity(context,7813,it,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }
   ids.forEach { id ->
    val views=RemoteViews(context.packageName,R.layout.widget_verse)
    views.setTextViewText(R.id.verse_text,verse.text)
    views.setTextViewText(R.id.verse_source,verse.source)
    if(click!=null) views.setOnClickPendingIntent(R.id.widget_root,click)
    views.setContentDescription(R.id.widget_root,verse.text+"، "+verse.source)
    manager.updateAppWidget(id,views)
   }
  }
  private fun pending(context: Context): PendingIntent {
   val intent=Intent(context,MishkatVerseWidgetProvider::class.java).setAction(ACTION_NEXT)
   return PendingIntent.getBroadcast(context,REQUEST,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  }
 }
 private data class Verse(val text:String,val source:String)
}
