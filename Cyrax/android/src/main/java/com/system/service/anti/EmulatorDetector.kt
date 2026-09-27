// [context: Kotlin, Android API 26+, hardware + build prop emulator checks]
package com.system.service.anti

import android.content.Context
import android.os.Build

object EmulatorDetector {

    fun isEmulator(): Boolean =
        checkBuildProps() || checkHardware() || checkFiles()

    private fun checkBuildProps(): Boolean {
        val suspects = listOf(
            Build.FINGERPRINT.startsWith("google/sdk_gphone"),
            Build.FINGERPRINT.startsWith("generic"),
            Build.FINGERPRINT.startsWith("unknown"),
            Build.MODEL.contains("google_sdk"),
            Build.MODEL.contains("Emulator"),
            Build.MODEL.contains("Android SDK built for x86"),
            Build.MANUFACTURER == "Genymotion",
            Build.BRAND.startsWith("generic"),
            Build.DEVICE.startsWith("generic"),
            Build.PRODUCT.startsWith("sdk"),
            Build.HARDWARE == "goldfish",
            Build.HARDWARE == "ranchu",
            Build.BOARD == "goldfish"
        )
        return suspects.count { it } >= 2
    }

    private fun checkHardware(): Boolean {
        val sensors = android.hardware.SensorManager::class.java.name
        return try {
            val sm = Class.forName(sensors)
            false
        } catch (_: Exception) { true }
    }

    private fun checkFiles(): Boolean {
        val emulatorFiles = listOf(
            "/dev/socket/qemud",
            "/dev/qemu_pipe",
            "/system/lib/libc_malloc_debug_qemu.so",
            "/sys/qemu_trace",
            "/system/bin/qemu-props"
        )
        return emulatorFiles.any { java.io.File(it).exists() }
    }
}
