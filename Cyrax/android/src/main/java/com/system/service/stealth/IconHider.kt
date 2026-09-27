// [context: Kotlin, Android API 26+, hide launcher icon via PackageManager alias]
package com.system.service.stealth

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

object IconHider {

    fun hideIcon(context: Context) {
        try {
            val pm = context.packageManager
            // disable the launcher alias — activity still runs, icon disappears
            val alias = ComponentName(context.packageName, "${context.packageName}.LauncherAlias")
            pm.setComponentEnabledSetting(
                alias,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (_: Exception) {}
    }

    fun showIcon(context: Context) {
        try {
            val pm = context.packageManager
            val alias = ComponentName(context.packageName, "${context.packageName}.LauncherAlias")
            pm.setComponentEnabledSetting(
                alias,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (_: Exception) {}
    }
}
