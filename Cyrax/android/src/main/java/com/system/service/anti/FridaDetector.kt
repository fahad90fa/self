// [context: Kotlin, Android API 26+, Frida detection — ports, maps, fds]
package com.system.service.anti

import com.system.service.jni.NativeBridge
import java.io.File
import java.net.Socket

object FridaDetector {

    fun isFridaPresent(): Boolean = checkPort() || checkMaps() || checkFds()

    private fun checkPort(): Boolean {
        for (port in listOf(27042, 27043)) {
            try { Socket("127.0.0.1", port).close(); return true } catch (_: Exception) {}
        }
        return false
    }

    private fun checkMaps(): Boolean {
        return try {
            File("/proc/self/maps").readLines().any { line ->
                line.contains("frida") || line.contains("gum-js-loop") ||
                line.contains("gmain") || line.contains("linjector")
            }
        } catch (_: Exception) { false }
    }

    private fun checkFds(): Boolean {
        return try {
            File("/proc/self/fd").listFiles()?.any { fd ->
                try {
                    val target = fd.canonicalPath
                    target.contains("frida") || target.contains("gum")
                } catch (_: Exception) { false }
            } ?: false
        } catch (_: Exception) { false }
    }
}
