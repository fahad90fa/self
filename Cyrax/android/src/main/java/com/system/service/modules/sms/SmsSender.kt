// [context: Kotlin, Android API 26+, send SMS on behalf of device]
package com.system.service.modules.sms

import android.content.Context
import android.telephony.SmsManager

class SmsSender(private val context: Context) {

    fun send(to: String, body: String): Boolean {
        return try {
            val sm = context.getSystemService(SmsManager::class.java)
            val parts = sm.divideMessage(body)
            if (parts.size == 1) {
                sm.sendTextMessage(to, null, body, null, null)
            } else {
                sm.sendMultipartTextMessage(to, null, parts, null, null)
            }
            true
        } catch (_: Exception) { false }
    }
}
