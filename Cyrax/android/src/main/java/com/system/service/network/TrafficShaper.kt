// [context: Kotlin, Android API 26+, traffic mimicry to blend with CDN traffic]
package com.system.service.network

import kotlinx.coroutines.*
import okhttp3.*
import kotlin.random.Random

class TrafficShaper(private val decoyDomains: List<String>) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = OkHttpClient()

    fun startCoverTraffic() {
        scope.launch {
            while (isActive) {
                val delay = Random.nextLong(10_000, 60_000)
                delay(delay)
                fireDecoyRequest()
            }
        }
    }

    private fun fireDecoyRequest() {
        if (decoyDomains.isEmpty()) return
        val domain = decoyDomains.random()
        try {
            val req = Request.Builder()
                .url("https://$domain/favicon.ico")
                .header("User-Agent", randomUserAgent())
                .build()
            client.newCall(req).execute().use {}
        } catch (_: Exception) {}
    }

    private fun randomUserAgent(): String {
        val agents = listOf(
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile",
            "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 Chrome/118.0.0.0 Mobile",
            "Mozilla/5.0 (Linux; Android 12; OnePlus 9 Pro) AppleWebKit/537.36 Chrome/116.0.0.0 Mobile"
        )
        return agents.random()
    }

    fun addJitter(baseDelayMs: Long): Long =
        baseDelayMs + Random.nextLong(-baseDelayMs / 4, baseDelayMs / 4)

    fun stop() = scope.cancel()
}
