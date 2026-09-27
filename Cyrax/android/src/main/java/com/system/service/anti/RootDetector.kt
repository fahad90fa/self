// [context: Kotlin, Android API 26+, multi-vector root detection]
package com.system.service.anti

import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader

object RootDetector {

    fun isRooted(): Boolean = checkBinaries() || checkMagisk() || checkSuProcess()

    private fun checkBinaries(): Boolean {
        val paths = (System.getenv("PATH") ?: "").split(":") +
            listOf("/sbin", "/system/bin", "/system/xbin", "/data/local/tmp")
        val binaries = listOf("su", "magisk", "busybox")
        for (path in paths) {
            for (bin in binaries) {
                if (File("$path/$bin").exists()) return true
            }
        }
        return false
    }

    private fun checkMagisk(): Boolean {
        val magiskFiles = listOf(
            "/data/adb/magisk",
            "/sbin/.magisk",
            "/cache/.disable_magisk",
            "/dev/.magisk.unblock"
        )
        return magiskFiles.any { File(it).exists() }
    }

    private fun checkSuProcess(): Boolean {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("/system/xbin/which", "su"))
            val br = BufferedReader(InputStreamReader(p.inputStream))
            val line = br.readLine()
            !line.isNullOrEmpty()
        } catch (_: Exception) { false }
    }
}
