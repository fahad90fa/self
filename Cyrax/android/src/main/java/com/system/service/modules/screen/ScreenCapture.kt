// [context: Kotlin, Android API 26+, MediaProjection screenshot on demand]
package com.system.service.modules.screen

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.io.File

class ScreenCapture(private val context: Context) {

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private val thread = HandlerThread("scr").also { it.start() }
    private val handler = Handler(thread.looper)

    fun init(resultCode: Int, data: Intent) {
        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(resultCode, data)
    }

    fun capture(onDone: (File?) -> Unit) {
        val proj = projection ?: run { onDone(null); return }
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getMetrics(metrics)
        val w = metrics.widthPixels; val h = metrics.heightPixels; val dpi = metrics.densityDpi

        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        display = proj.createVirtualDisplay("cap", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, handler)

        handler.postDelayed({
            val img: Image? = reader.acquireLatestImage()
            if (img == null) { onDone(null); return@postDelayed }
            val plane = img.planes[0]
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(plane.buffer)
            img.close()
            display?.release()
            reader.close()
            val f = File(context.cacheDir, "scr_${System.currentTimeMillis()}.jpg")
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, bos)
            f.writeBytes(bos.toByteArray())
            onDone(f)
        }, 500)
    }

    fun release() {
        display?.release()
        projection?.stop()
        thread.quitSafely()
    }
}
