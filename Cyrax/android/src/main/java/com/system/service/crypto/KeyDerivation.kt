// [context: Kotlin, Android API 26+, HKDF-style key derivation from env fingerprint]
package com.system.service.crypto

import android.os.Build
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object KeyDerivation {

    fun deriveDeviceKey(): ByteArray {
        val fingerprint = collectFingerprint()
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(fingerprint.toByteArray())
    }

    fun deriveSessionKey(deviceKey: ByteArray, serverNonce: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(deviceKey, "HmacSHA256"))
        mac.update(serverNonce)
        return mac.doFinal()
    }

    fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = mutableListOf<Byte>()
        var t = ByteArray(0)
        var i = 1
        while (out.size < length) {
            mac.reset()
            mac.update(t)
            mac.update(info)
            mac.update(i.toByte())
            t = mac.doFinal()
            out.addAll(t.toList())
            i++
        }
        return out.take(length).toByteArray()
    }

    private fun collectFingerprint(): String =
        "${Build.FINGERPRINT}:${Build.BOARD}:${Build.BOOTLOADER}:${Build.HARDWARE}"
}
