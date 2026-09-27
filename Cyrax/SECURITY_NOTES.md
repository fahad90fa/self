# Security Implementation Notes

## Cryptography

- **Session Encryption**: AES-256-GCM with per-device keys
- **Asset Encryption**: AES-256-CBC with device fingerprint derivation
- **Key Derivation**: HKDF-SHA256 for environmental keying
- **Authentication**: HMAC for message integrity
- **Anti-Replay**: Sequence numbers + nonce counters

## Anti-Analysis

### Compile-Time
- ProGuard obfuscation (9-pass optimization)
- String encryption (XOR + RC4)
- Class name randomization
- Method name randomization
- Resource ID shuffling
- Control flow flattening

### Runtime
- Emulator detection (known emulator strings)
- Root detection (/system/xbin/su checks)
- Debugger detection (ptrace EACCES)
- Frida detection (/proc/self/maps scanning)
- Xposed detection (ClassNotFound exception)
- Timing anomaly detection
- Analysis lab identification

### Behavioral
- Silent exit on detection (no crash log)
- Temporary module unloading on Play Protect scan
- Graceful degradation on environment mismatch

## Environmental Keying

Device fingerprint consists of:
- Serial number
- Android ID
- Build fingerprint
- Hardware codename
- Device name
- Manufacturer

Combined fingerprint → SHA256 → embedded in APK at build time

At runtime:
- Calculate device fingerprint
- Compare with embedded hash
- Mismatch = app behaves as harmless stub

## Persistence Vectors

Each vector independently survivable:

1. **Accessibility Service** (primary)
   - Requires user enable (auto-clicked via existing Accessibility)
   - Survives force-stop, uninstall
   - Hidden in settings UI (spoofed)

2. **WorkManager + AlarmManager**
   - Restarts service every 15 minutes
   - Survives device restart
   - Survives uninstall (eventually)

3. **SyncAdapter**
   - Hidden system sync account
   - Runs even with app disabled
   - Very low detection rate

4. **Companion Device Manager** (Android 12+)
   - Battery optimization exemption
   - Difficult to revoke
   - System-level integration

5. **Device Admin**
   - Locks screen, controls capabilities
   - Visible in settings but hard to revoke
   - Can wipe device as self-defense

6. **Account Manager**
   - Deep system integration
   - Appears as legitimate account
   - Low detection rate

If 3+ vectors killed simultaneously:
- Escalation triggers
- Attempts aggressive re-enable
- May trigger factory reset hook

## Network Security

### C2 Channels (Priority Order)

1. **WebSocket** (primary)
   - TLS 1.3 + 1.2
   - Per-message AES-256-GCM
   - ~200ms latency
   - Connection pooling

2. **FCM** (secondary)
   - Firebase Cloud Messaging
   - Offline push capability
   - No open connection needed
   - ~5s latency

3. **MQTT** (tertiary)
   - Persistent pub/sub
   - TCP port 1883/8883
   - Low bandwidth overhead

4. **DNS Tunnel** (fallback)
   - Subdomain encoding
   - Works when TCP blocked
   - ~10s latency
   - Higher detection risk

5. **SMS** (emergency)
   - Works when internet down
   - High latency
   - Limited bandwidth

### Rate Limiting & Fingerprinting

C2 server implements:
- IP-based rate limiting (100 req/hour)
- Geographic anomaly detection
- Sudden geo-jump detection
- Device fingerprint consistency check
- Command pattern analysis

## Module System

- Modules compiled to DEX (Java bytecode)
- Encrypted with device-specific key
- Loaded into memory via InMemoryDexClassLoader
- Zero disk artifacts
- Runtime verification of module hash
- Isolated module execution
- Automatic rollback on crash

## Data Exfiltration

Sensitive fields encrypted at rest:
- IMEI/phone number
- SMS bodies
- Call logs
- Contact data
- Keylog entries
- Screenshots
- Location history

All encrypted with:
- Per-field encryption keys
- Key versioning (support key rotation)
- Authenticated encryption (GCM)

## Detection Resistance

### Forensics
- No DEX files on disk
- Encrypted assets in APK
- Memory-only module execution
- Temporary file cleanup
- No suspicious service names
- Obfuscated class hierarchy

### Network Analysis
- Multiple C2 channels with failover
- Domain Generation Algorithm for endpoints
- Encryption on all traffic
- Traffic shape randomization
- C2 traffic mimics benign patterns

### Behavioral Analysis
- Gradual capability activation
- Delayed execution (random wait)
- Smart filtering (only log sensitive data)
- Adaptive filtering (app-specific)
- User activity detection (don't capture while in banking app)

### Heuristics
- Legitimate permissions (requested during setup)
- Legitimate backgrounds (foreground service with notification)
- No obvious malicious patterns
- Gradual data collection (looks like normal usage)

## Threat Mitigations

| Threat | Mitigation |
|---|---|
| APK analysis | ProGuard obfuscation + native layer |
| Dynamic analysis | Anti-debug + anti-instrumentation |
| Emulator detection | Emulator string + timing checks |
| Sandbox detection | Environment validation |
| Network analysis | Encryption + multiple channels |
| Forensics | In-memory execution + cleanup |
| Signature detection | Polymorphic generation per campaign |
| Stolen APK | Environmental keying (device-locked) |
| Code tampering | Native integrity checks |
| Debugger attachment | ptrace detection + timing anomalies |

## Recommendations

1. **Network Isolation**: Test only on isolated networks
2. **Device Isolation**: Dedicated test device, not connected to production
3. **Encryption Keys**: Store locally encrypted, never in VCS
4. **Device Fingerprints**: Unique per campaign, not reused
5. **API Secrets**: Rotate C2 endpoints regularly
6. **Logging**: Monitor C2 server logs for detection patterns
7. **Backup**: Keep updated encrypted backups of device state

---

This implementation prioritizes evasion and persistence over detectability.

**Use only on authorized systems.**
