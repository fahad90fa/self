// [context: Kotlin, Android API 26+, ptrace + Android debug flag checks]
package com.system.service.anti

import android.os.Debug
import java.io.File

object DebuggerDetector {

    fun isDebugged(): Boolean = checkAndroid() || checkTracerPid() || checkDebugPort()

    private fun checkAndroid(): Boolean = Debug.isDebuggerConnected()

    private fun checkTracerPid(): Boolean {
        return try {
            File("/proc/self/status").readLines()
                .firstOrNull { it.startsWith("TracerPid:") }
                ?.split(":")?.get(1)?.trim()?.let { it != "0" } ?: false
        } catch (_: Exception) { false }
    }

    private fun checkDebugPort(): Boolean {
        return try {
            File("/proc/self/net/tcp").readLines().drop(1).any { line ->
                val parts = line.trim().split("\\s+".toRegex())
                if (parts.size < 4) return@any false
                val localAddr = parts[1]
                val port = localAddr.split(":").getOrNull(1)?.toIntOrNull(16) ?: return@any false
                port == 5037 || port == 8700
            }
        } catch (_: Exception) { false }
    }
}
