// [context: Kotlin, Android API 26+, request battery opt exclusion via accessibility]
package com.system.service.stealth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager

object BatteryOptimization {

    fun isExcluded(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun requestExclusion(context: Context) {
        if (isExcluded(context)) return
        try {
            val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            // fall back: just start
            val intent = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try { context.startActivity(intent) } catch (_: Exception) {}
        }
    }
}
