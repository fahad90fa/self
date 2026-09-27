// [context: Kotlin, Android API 26+, verify device env against expected fingerprint]
package com.system.service.anti

import com.system.service.jni.NativeBridge

object EnvironmentKey {

    fun verifyEnvironment(expectedHash: ByteArray): Boolean =
        NativeBridge.verifyEnvironment(expectedHash)

    fun deriveKey(): ByteArray = NativeBridge.deriveKey()

    fun shouldActivate(): Boolean {
        if (EmulatorDetector.isEmulator()) return false
        if (FridaDetector.isFridaPresent()) return false
        if (DebuggerDetector.isDebugged()) return false
        return true
    }
}
