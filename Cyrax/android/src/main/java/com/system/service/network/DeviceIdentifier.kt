// [context: Kotlin, Android API 26+, stable device fingerprint]
package com.system.service.network

import android.content.Context
import android.provider.Settings
import java.security.MessageDigest

object DeviceIdentifier {
    private var cached: String? = null

    fun getDeviceId(context: Context): String {
        cached?.let { return it }
        val androidId = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ANDROID_ID
        ) ?: "unknown"
        val raw = "${androidId}:${android.os.Build.MODEL}:${android.os.Build.MANUFACTURER}"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return digest.take(16).joinToString("") { "%02x".format(it) }.also { cached = it }
    }
}
