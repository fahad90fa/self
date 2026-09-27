// [context: Kotlin, Android API 26+, JNI bridge to native Rust lib]
package com.system.service.jni

object NativeBridge {

    init {
        System.loadLibrary("cyrax_native")
    }

    // env keying
    external fun deriveKey(): ByteArray
    external fun verifyEnvironment(expectedHash: ByteArray): Boolean

    // anti-frida
    external fun isHooked(funcAddr: Long): Boolean
    external fun scanMaps(target: String): Boolean
    external fun checkFridaPort(): Boolean

    // dex loader
    external fun loadDexInMemory(dexBytes: ByteArray, classLoader: Any): Any?
    external fun zeroDexMemory(dexBytes: ByteArray)

    // self-destruct
    external fun nativeWipe(dirPath: String)
    external fun zeroFillFile(path: String)

    // anti-debug
    external fun checkTracerPid(): Boolean
    external fun installSigTrap(): Boolean
}
