// [context: Kotlin, Android API 26+, periodic beacon with device telemetry]
package com.system.service.network

import android.content.Context
import android.os.BatteryManager
import kotlinx.coroutines.*
import org.json.JSONObject

class Heartbeat(
    private val context: Context,
    private val send: (ByteArray) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start(intervalMs: Long = 300_000L) {
        scope.launch {
            while (isActive) {
                val beacon = buildBeacon()
                send(beacon.toString().toByteArray())
                delay(intervalMs)
            }
        }
    }

    private fun buildBeacon(): JSONObject {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return JSONObject().apply {
            put("type", "heartbeat")
            put("device_id", DeviceIdentifier.getDeviceId(context))
            put("ts", System.currentTimeMillis())
            put("battery", battery)
            put("sdk", android.os.Build.VERSION.SDK_INT)
            put("model", android.os.Build.MODEL)
        }
    }

    fun stop() = scope.cancel()
}
