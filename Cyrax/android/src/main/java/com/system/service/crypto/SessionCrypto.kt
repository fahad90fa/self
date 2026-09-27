// [context: Kotlin, Android API 26+, AES-256-GCM session encryption]
package com.system.service.crypto

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

object SessionCrypto {

    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    fun encrypt(data: ByteArray, key: ByteArray): ByteArray {
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        val ct = cipher.doFinal(data)
        return iv + ct
    }

    fun decrypt(data: ByteArray, key: ByteArray): ByteArray? {
        if (data.size < IV_BYTES + 1) return null
        return try {
            val iv = data.sliceArray(0 until IV_BYTES)
            val ct = data.sliceArray(IV_BYTES until data.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(ct)
        } catch (_: Exception) { null }
    }

    fun encryptToBase64(data: ByteArray, key: ByteArray): String =
        Base64.encodeToString(encrypt(data, key), Base64.NO_WRAP)

    fun decryptFromBase64(data: String, key: ByteArray): ByteArray? =
        decrypt(Base64.decode(data, Base64.NO_WRAP), key)
}
