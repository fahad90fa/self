// [context: Kotlin, shared JNI data types]
package com.system.service.jni

data class NativeResult(val success: Boolean, val data: ByteArray? = null, val error: String? = null)

enum class WipeMode { FILES_ONLY, DIRS_AND_FILES, FULL_APP }

data class EnvHash(val raw: ByteArray, val hex: String = raw.joinToString("") { "%02x".format(it) })
